package com.yupi.yuaiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yupi.yuaiagent.model.dto.SunsetEvaluation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 天气与落日决策引擎，数据源为免鉴权的 Open-Meteo
 */
@Service
@Slf4j
public class WeatherService {

    /**
     * 悉尼天文台山公园，视野开阔且可同时收进海港大桥
     */
    private static final String SUNSET_SPOT = "Observatory Hill Park";

    private static final String FORECAST_PATH = "/v1/forecast";

    /**
     * 悉尼市中心坐标
     */
    private static final double LATITUDE = -33.8688;
    private static final double LONGITUDE = 151.2093;

    private static final String DAILY_FIELDS = "sunset,precipitation_probability_max,cloudcover_mean";
    private static final String TIMEZONE = "Australia/Sydney";

    /**
     * 降水概率上限，超过则霞光大概率被雨云吃掉
     */
    private static final int RAIN_PROB_LIMIT = 30;

    /**
     * 云量适宜区间，过低缺少承接霞光的云，过高则完全遮挡
     */
    private static final int CLOUD_COVER_MIN = 15;
    private static final int CLOUD_COVER_MAX = 75;

    /**
     * 黄金窗口：日落前 30 分钟至日落后 15 分钟
     */
    private static final int GOLDEN_HOUR_BEFORE_MINUTES = 30;
    private static final int GOLDEN_HOUR_AFTER_MINUTES = 15;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final RestClient restClient;

    public WeatherService(RestClient.Builder restClientBuilder) {
        // 复用容器内已配置的消息转换器
        this.restClient = restClientBuilder.baseUrl("https://api.open-meteo.com").build();
    }

    /**
     * 评估当日落日观赏价值
     *
     * @return 决策结果；外部接口异常时返回不影响主流程的兜底结果
     */
    public SunsetEvaluation evaluateTodaySunset() {
        try {
            // 逐个传参交由 UriBuilder 编码，避免手写 %2F 被二次编码
            DailyForecast forecast = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(FORECAST_PATH)
                            .queryParam("latitude", LATITUDE)
                            .queryParam("longitude", LONGITUDE)
                            .queryParam("daily", DAILY_FIELDS)
                            .queryParam("timezone", TIMEZONE)
                            .queryParam("forecast_days", 1)
                            .build())
                    .retrieve()
                    .body(DailyForecast.class);
            return evaluate(forecast);
        } catch (Exception e) {
            log.warn("Open-Meteo 落日数据获取失败，返回兜底结果", e);
            return fallback();
        }
    }

    private SunsetEvaluation evaluate(DailyForecast forecast) {
        Daily daily = forecast == null ? null : forecast.daily();
        LocalDateTime sunset = parseSunset(daily);
        Integer cloudCover = firstValue(daily == null ? null : daily.cloudCover());
        Integer rainProb = firstValue(daily == null ? null : daily.rainProb());
        if (sunset == null || cloudCover == null || rainProb == null) {
            log.warn("Open-Meteo 返回数据不完整，返回兜底结果");
            return fallback();
        }

        String sunsetTime = sunset.format(TIME_FORMATTER);
        String goldenHourWindow = buildGoldenHourWindow(sunset.toLocalTime());
        boolean recommended = rainProb < RAIN_PROB_LIMIT
                && cloudCover >= CLOUD_COVER_MIN
                && cloudCover <= CLOUD_COVER_MAX;

        if (recommended) {
            return new SunsetEvaluation(
                    true,
                    sunsetTime,
                    goldenHourWindow,
                    cloudCover,
                    rainProb,
                    "今日多云微晴，晚霞概率高",
                    SUNSET_SPOT,
                    "建议提前 20 分钟到场，用海港大桥作前景压住画面右侧，日落后再等 10 分钟拍蓝调时刻。"
            );
        }
        return new SunsetEvaluation(
                false,
                sunsetTime,
                goldenHourWindow,
                cloudCover,
                rainProb,
                buildGentleReason(cloudCover, rainProb),
                SUNSET_SPOT,
                buildGentlePhotoTip(cloudCover, rainProb)
        );
    }

    /**
     * 委婉说明今日为何不值得专程等落日
     */
    private String buildGentleReason(int cloudCover, int rainProb) {
        if (rainProb >= RAIN_PROB_LIMIT) {
            return "今日降水概率约 %d%%，雨云容易吸走霞光，专程等落日的性价比不高".formatted(rainProb);
        }
        if (cloudCover > CLOUD_COVER_MAX) {
            return "今日云量约 %d%%，阴云偏厚会把霞光整片遮住，落日大概率只是天色渐暗".formatted(cloudCover);
        }
        return "今日云量仅约 %d%%，天空过于通透、缺少承接霞光的云，落日会比较素净".formatted(cloudCover);
    }

    private String buildGentlePhotoTip(int cloudCover, int rainProb) {
        if (rainProb >= RAIN_PROB_LIMIT) {
            return "如果仍想去，雨后湿地面反光很适合拍城市倒影，记得给镜头准备防水布。";
        }
        if (cloudCover > CLOUD_COVER_MAX) {
            return "厚云天更适合拍低饱和的冷调建筑几何，不必执着于天空。";
        }
        return "晴空落日建议改拍剪影或长焦压缩的城市线条，画面会比拍霞光更耐看。";
    }

    private String buildGoldenHourWindow(LocalTime sunsetTime) {
        LocalTime start = sunsetTime.minusMinutes(GOLDEN_HOUR_BEFORE_MINUTES);
        LocalTime end = sunsetTime.plusMinutes(GOLDEN_HOUR_AFTER_MINUTES);
        return "%s - %s".formatted(start.format(TIME_FORMATTER), end.format(TIME_FORMATTER));
    }

    private LocalDateTime parseSunset(Daily daily) {
        String rawSunset = firstValue(daily == null ? null : daily.sunset());
        if (rawSunset == null || rawSunset.isBlank()) {
            return null;
        }
        try {
            // Open-Meteo 返回本地时间，形如 2026-09-16T17:47
            return LocalDateTime.parse(rawSunset);
        } catch (Exception e) {
            log.warn("无法解析日落时间: {}", rawSunset);
            return null;
        }
    }

    private <T> T firstValue(List<T> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * 网络或数据异常时的兜底结果，保证调用方主流程不中断
     */
    private SunsetEvaluation fallback() {
        return new SunsetEvaluation(
                false,
                null,
                null,
                null,
                null,
                "暂时拿不到实时天气数据，建议出发前再确认当日云量与降水情况",
                SUNSET_SPOT,
                "若临时决定前往，可留出日落前后各半小时的弹性时间。"
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DailyForecast(Daily daily) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Daily(
            List<String> sunset,
            @JsonProperty("precipitation_probability_max") List<Integer> rainProb,
            @JsonProperty("cloudcover_mean") List<Integer> cloudCover
    ) {
    }
}
