package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.config.WeatherRestClientConfig;
import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 气象链路测试：用 MockRestServiceServer 顶替 Open-Meteo，验证解析、Caffeine 缓存与降级。
 * <p>
 * 这里刻意不用 @SpringBootTest，只拉起「缓存 + 气象」两个 Bean 的最小上下文，
 * 避免加载 Spring AI 的 ChatClient 而需要真实 API Key。
 */
@SpringJUnitConfig(WeatherServiceTest.WeatherTestConfig.class)
@DisplayName("WeatherService - Caffeine 缓存与外部故障降级")
class WeatherServiceTest {

    /**
     * 降水 10% + 云量 40%，落在推荐区间内
     */
    private static final String GOLDEN_SUNSET_JSON = """
            {
              "latitude": -33.875,
              "longitude": 151.2093,
              "timezone": "Australia/Sydney",
              "daily": {
                "time": ["2026-09-21"],
                "sunset": ["2026-09-21T18:05"],
                "precipitation_probability_max": [10],
                "cloudcover_mean": [40]
              }
            }
            """;

    @Autowired
    private WeatherService weatherService;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Spring 测试上下文在方法间复用，必须手工清空缓存与 Mock 期望，保证用例彼此独立。
     */
    @BeforeEach
    void resetState() {
        server.reset();
        weatherCache().clear();
    }

    @Test
    @DisplayName("两次调用只发生一次 HTTP 交互，第二次直接命中 Caffeine")
    void testWeatherCaching() {
        expectForecastRequest().andRespond(withSuccess(GOLDEN_SUNSET_JSON, MediaType.APPLICATION_JSON));

        SunsetEvaluation first = weatherService.evaluateTodaySunset("zh");
        SunsetEvaluation second = weatherService.evaluateTodaySunset("zh");

        // 期望值设为 once()，若第二次真的回源，MockRestServiceServer 会当场抛断言失败
        server.verify();

        assertThat(first.recommended()).isTrue();
        assertThat(first.sunsetTime()).isEqualTo("18:05");
        assertThat(first.goldenHourWindow()).isEqualTo("17:35 - 18:20");
        assertThat(first.cloudCover()).isEqualTo(40);
        assertThat(first.rainProb()).isEqualTo(10);
        assertThat(first.recommendedSpot()).isEqualTo("Observatory Hill Park");
        assertThat(second).isEqualTo(first);
        assertThat(weatherCache().getNativeCache().estimatedSize())
                .as("同一坐标同一天只应留下一条缓存")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("缓存的是与语言无关的原始预报，中英文共用同一次回源")
    void testCacheIsLanguageIndependent() {
        expectForecastRequest().andRespond(withSuccess(GOLDEN_SUNSET_JSON, MediaType.APPLICATION_JSON));

        SunsetEvaluation zh = weatherService.evaluateTodaySunset("zh");
        SunsetEvaluation en = weatherService.evaluateTodaySunset("en");

        server.verify();

        assertThat(zh.recommended()).isEqualTo(en.recommended());
        assertThat(zh.sunsetTime()).isEqualTo(en.sunsetTime());
        assertThat(zh.recommendationReason()).isNotEqualTo(en.recommendationReason());
        // 英文模式下不得混入中文字符
        assertThat(en.recommendationReason()).isNotBlank().matches("\\p{ASCII}+");
        assertThat(en.photoTip()).isNotBlank().matches("\\p{ASCII}+");
    }

    @Test
    @DisplayName("Open-Meteo 返回 500 时不抛异常，优雅降级为兜底结果")
    void testWeatherFallbackOnHttpError() {
        expectForecastRequest().andRespond(withServerError());

        SunsetEvaluation evaluation = weatherService.evaluateTodaySunset("zh");

        server.verify();
        assertDegraded(evaluation);
    }

    @Test
    @DisplayName("读超时同样走降级分支，不会把异常抛给上层行程链路")
    void testWeatherFallbackOnTimeout() {
        expectForecastRequest().andRespond(withException(new SocketTimeoutException("Read timed out")));

        // 方法本身不抛异常即为通过，异常会直接让用例失败
        SunsetEvaluation evaluation = weatherService.evaluateTodaySunset("zh");

        server.verify();
        assertDegraded(evaluation);
    }

    @Test
    @DisplayName("响应字段缺失时按数据不完整降级")
    void testWeatherFallbackOnIncompletePayload() {
        expectForecastRequest().andRespond(withSuccess("{\"daily\":{}}", MediaType.APPLICATION_JSON));

        SunsetEvaluation evaluation = weatherService.evaluateTodaySunset("zh");

        server.verify();
        assertDegraded(evaluation);
    }

    @Test
    @DisplayName("失败结果不入缓存，下一次请求仍会重试回源")
    void testFailureIsNotCached() {
        expectForecastRequest().andRespond(withServerError());
        expectForecastRequest().andRespond(withSuccess(GOLDEN_SUNSET_JSON, MediaType.APPLICATION_JSON));

        SunsetEvaluation degraded = weatherService.evaluateTodaySunset("zh");
        SunsetEvaluation recovered = weatherService.evaluateTodaySunset("zh");

        // 两条期望都被消费，说明 500 之后没有被缓存 30 分钟
        server.verify();
        assertThat(degraded.recommended()).isFalse();
        assertThat(recovered.recommended()).isTrue();
        assertThat(recovered.sunsetTime()).isEqualTo("18:05");
    }

    private ResponseActions expectForecastRequest() {
        return server.expect(once(), requestTo(containsString(OpenMeteoWeatherService.FORECAST_PATH)))
                .andExpect(method(HttpMethod.GET));
    }

    private void assertDegraded(SunsetEvaluation evaluation) {
        assertThat(evaluation).isNotNull();
        assertThat(evaluation.recommended()).isFalse();
        assertThat(evaluation.sunsetTime()).isNull();
        assertThat(evaluation.goldenHourWindow()).isNull();
        assertThat(evaluation.cloudCover()).isNull();
        assertThat(evaluation.rainProb()).isNull();
        assertThat(evaluation.recommendationReason()).contains("暂时拿不到实时天气数据");
        assertThat(evaluation.recommendedSpot()).isEqualTo("Observatory Hill Park");
        assertThat(evaluation.photoTip()).isNotBlank();
    }

    private CaffeineCache weatherCache() {
        assertThat(cacheManager.getCache(CacheConfig.WEATHER_CACHE))
                .as("weatherCache 必须已注册")
                .isInstanceOf(CaffeineCache.class);
        return (CaffeineCache) cacheManager.getCache(CacheConfig.WEATHER_CACHE);
    }

    /**
     * 最小上下文：Caffeine 缓存 + 绑定了 MockRestServiceServer 的 RestClient。
     */
    @Configuration
    @Import(CacheConfig.class)
    static class WeatherTestConfig {

        @Bean
        RestClient.Builder openMeteoRestClientBuilder() {
            return RestClient.builder().baseUrl(WeatherRestClientConfig.OPEN_METEO_BASE_URL);
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        /**
         * 形参里的 server 不参与构建，仅用于保证 Mock 请求工厂先于 build() 装上。
         */
        @Bean
        RestClient openMeteoRestClient(RestClient.Builder builder, MockRestServiceServer server) {
            return builder.build();
        }

        @Bean
        OpenMeteoWeatherService openMeteoWeatherService(RestClient openMeteoRestClient) {
            return new OpenMeteoWeatherService(openMeteoRestClient);
        }

        @Bean
        WeatherService weatherService(OpenMeteoWeatherService openMeteoWeatherService) {
            return new WeatherService(openMeteoWeatherService);
        }
    }
}
