package com.yeah.yeahaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Open-Meteo 专用 RestClient。
 * <p>
 * 单独成 Bean 而非在 Service 构造函数里拼装，一是把超时策略收敛到配置层，
 * 二是测试可以直接注入绑定了 MockRestServiceServer 的客户端，无需触碰真实网络。
 */
@Configuration
public class WeatherRestClientConfig {

    public static final String OPEN_METEO_REST_CLIENT = "openMeteoRestClient";

    public static final String OPEN_METEO_BASE_URL = "https://api.open-meteo.com";

    /**
     * 外部网络抖动时快速失败，避免拖垮 Tomcat 工作线程
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean(OPEN_METEO_REST_CLIENT)
    public RestClient openMeteoRestClient(RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        // 复用容器内已配置的消息转换器，仅替换请求工厂以挂上超时
        return restClientBuilder.clone()
                .baseUrl(OPEN_METEO_BASE_URL)
                .requestFactory(requestFactory)
                .build();
    }
}
