package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.config.WeatherRestClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Open-Meteo 气象数据访问层。
 * <p>
 * 独立成 Bean 而非放在 {@link WeatherService} 内部方法上，是因为 @Cacheable 依赖 Spring 代理，
 * 同类内部自调用会绕过代理导致缓存失效。这里只缓存与语言无关的原始预报，
 * 中英文文案由上层根据同一份数据渲染。
 */
@Service
@Slf4j
public class OpenMeteoWeatherService {

    public static final String FORECAST_PATH = "/v1/forecast";

    private static final String DAILY_FIELDS = "sunset,precipitation_probability_max,cloudcover_mean";

    private static final String TIMEZONE = "Australia/Sydney";

    private final RestClient restClient;

    public OpenMeteoWeatherService(
            @Qualifier(WeatherRestClientConfig.OPEN_METEO_REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 查询指定坐标当日的日出日落与云雨概况。
     * <p>
     * 抛出异常时 Spring Cache 不会写入缓存，因此故障不会被固化 30 分钟。
     *
     * @param lat  纬度
     * @param lng  经度
     * @param date 目标日期，仅参与缓存键，跨天后自然失效
     * @return 原始预报数据
     */
    @Cacheable(value = CacheConfig.WEATHER_CACHE, key = "#lat + '_' + #lng + '_' + #date")
    public DailyForecast queryDailyForecast(double lat, double lng, LocalDate date) {
        log.info("回源 Open-Meteo 查询气象数据, lat={}, lng={}, date={}", lat, lng, date);
        // 逐个传参交由 UriBuilder 编码，避免手写 %2F 被二次编码
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path(FORECAST_PATH)
                        .queryParam("latitude", lat)
                        .queryParam("longitude", lng)
                        .queryParam("daily", DAILY_FIELDS)
                        .queryParam("timezone", TIMEZONE)
                        .queryParam("forecast_days", 1)
                        .build())
                .retrieve()
                .body(DailyForecast.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyForecast(Daily daily) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Daily(
            List<String> sunset,
            @JsonProperty("precipitation_probability_max") List<Integer> rainProb,
            @JsonProperty("cloudcover_mean") List<Integer> cloudCover
    ) {
    }
}
