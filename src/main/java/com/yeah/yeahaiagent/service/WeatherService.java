package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.SunsetEvaluation;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.service.OpenMeteoWeatherService.Daily;
import com.yeah.yeahaiagent.service.OpenMeteoWeatherService.DailyForecast;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 天气与落日决策引擎，数据源为免鉴权的 Open-Meteo
 */
@Service
@Slf4j
public class WeatherService {

    /**
     * 悉尼市中心坐标（只用来拉全市天气，不代表落日机位）
     */
    private static final double LATITUDE = -33.8688;
    private static final double LONGITUDE = 151.2093;

    private static final ZoneId SYDNEY_ZONE = ZoneId.of("Australia/Sydney");

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

    private final OpenMeteoWeatherService openMeteoWeatherService;

    public WeatherService(OpenMeteoWeatherService openMeteoWeatherService) {
        this.openMeteoWeatherService = openMeteoWeatherService;
    }

    /**
     * 评估当日落日观赏价值
     *
     * @param lang 输出语言，en / zh，空值回落英语
     * @return 决策结果；外部接口异常时返回不影响主流程的兜底结果
     */
    public SunsetEvaluation evaluateTodaySunset(String lang) {
        LanguageEnum language = LanguageEnum.fromCode(lang);
        try {
            // 缓存命中时不会产生任何外部 HTTP 交互
            DailyForecast forecast = openMeteoWeatherService.queryDailyForecast(
                    LATITUDE, LONGITUDE, LocalDate.now(SYDNEY_ZONE));
            return evaluate(forecast, language);
        } catch (Exception e) {
            log.warn("Open-Meteo 落日数据获取失败，返回兜底结果", e);
            return fallback(language);
        }
    }

    private SunsetEvaluation evaluate(DailyForecast forecast, LanguageEnum language) {
        Daily daily = forecast == null ? null : forecast.daily();
        LocalDateTime sunset = parseSunset(daily);
        Integer cloudCover = firstValue(daily == null ? null : daily.cloudCover());
        Integer rainProb = firstValue(daily == null ? null : daily.rainProb());
        if (sunset == null || cloudCover == null || rainProb == null) {
            log.warn("Open-Meteo 返回数据不完整，返回兜底结果");
            return fallback(language);
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
                    language.text("今日多云微晴，晚霞概率高",
                            "Partly cloudy today, high chance of sunset colour"),
                    null,
                    language.text(
                            "若行程会排到傍晚，在当天最后一站附近找朝西的海边或高点即可，不要跨区赶路。",
                            "If the day runs into evening, pick a west-facing headland or high point near the last stop. Do not cross town.")
            );
        }
        return new SunsetEvaluation(
                false,
                sunsetTime,
                goldenHourWindow,
                cloudCover,
                rainProb,
                buildGentleReason(cloudCover, rainProb, language),
                null,
                buildGentlePhotoTip(cloudCover, rainProb, language)
        );
    }

    /**
     * 委婉说明今日为何不值得专程等落日
     */
    private String buildGentleReason(int cloudCover, int rainProb, LanguageEnum language) {
        if (rainProb >= RAIN_PROB_LIMIT) {
            return language.text(
                    "今日降水概率约 %d%%，雨云容易吸走霞光，专程等落日的性价比不高".formatted(rainProb),
                    "Rain probability is about %d%%. Rain clouds will likely wash out the colour, so a dedicated sunset trip is not worth it.".formatted(rainProb));
        }
        if (cloudCover > CLOUD_COVER_MAX) {
            return language.text(
                    "今日云量约 %d%%，阴云偏厚会把霞光整片遮住，落日大概率只是天色渐暗".formatted(cloudCover),
                    "Cloud cover is about %d%%. Thick cloud will block the glow, so sunset will mostly just fade to dark.".formatted(cloudCover));
        }
        return language.text(
                "今日云量仅约 %d%%，天空过于通透、缺少承接霞光的云，落日会比较素净".formatted(cloudCover),
                "Cloud cover is only about %d%%. The sky is too clear to catch colour, so sunset will look clean but plain.".formatted(cloudCover));
    }

    private String buildGentlePhotoTip(int cloudCover, int rainProb, LanguageEnum language) {
        if (rainProb >= RAIN_PROB_LIMIT) {
            return language.text(
                    "如果仍想去，雨后湿地面反光很适合拍城市倒影，记得给镜头准备防水布。",
                    "If you still go, wet streets after rain are great for city reflections. Bring a rain cover for the lens.");
        }
        if (cloudCover > CLOUD_COVER_MAX) {
            return language.text(
                    "厚云天更适合拍低饱和的冷调建筑几何，不必执着于天空。",
                    "On a heavy-cloud day, shoot low-saturation cool-tone architecture instead of chasing the sky.");
        }
        return language.text(
                "晴空落日建议改拍剪影或长焦压缩的城市线条，画面会比拍霞光更耐看。",
                "On a clear-sky sunset, shoot silhouettes or compressed city lines with a telephoto. That will hold up better than chasing colour.");
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
    private SunsetEvaluation fallback(LanguageEnum language) {
        return new SunsetEvaluation(
                false,
                null,
                null,
                null,
                null,
                language.text(
                        "暂时拿不到实时天气数据，建议出发前再确认当日云量与降水情况",
                        "Live weather is unavailable. Recheck cloud cover and rain before you head out."),
                null,
                language.text(
                        "若行程会排到傍晚，再按当天最后一站就近选朝西的高点或海边。",
                        "If the day runs into evening, pick a west-facing high point or shoreline near the last stop.")
        );
    }
}
