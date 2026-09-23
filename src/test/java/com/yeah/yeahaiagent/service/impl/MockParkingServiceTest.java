package com.yeah.yeahaiagent.service.impl;

import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 纯本地停车适配器测试：不依赖 Spring 容器与任何外部网络。
 */
@DisplayName("MockParkingService - Haversine 距离与就近推荐")
class MockParkingServiceTest {

    /**
     * Newtown King St 一带坐标
     */
    private static final double NEWTOWN_LAT = -33.8970;
    private static final double NEWTOWN_LNG = 151.1795;

    private static final String NEWTOWN_GARAGE = "Newtown Council Car Park";

    /**
     * 同时匹配 "步行约 1 分钟 (56m)" 与 "~1 mins walk (56m)"
     */
    private static final Pattern WALKING_PATTERN = Pattern.compile("(\\d+)[^(]*\\((\\d+)m\\)");

    private MockParkingService parkingService;

    @BeforeEach
    void setUp() {
        parkingService = new MockParkingService();
    }

    @Test
    @DisplayName("Newtown 坐标应推荐 Newtown 本地车库，且步行预估合理")
    void testRecommendParkingForNewtown() {
        ParkingInfo info = parkingService.findNearbyParking(NEWTOWN_LAT, NEWTOWN_LNG, "Newtown", "zh");

        assertThat(info).isNotNull();
        assertThat(info.hasParkingRecommendation()).isTrue();
        assertThat(info.parkingName()).isEqualTo(NEWTOWN_GARAGE);
        assertThat(info.address()).contains("Newtown NSW 2042");
        assertThat(info.rating()).isNotNull().isBetween(0d, 5d);
        assertThat(info.tip()).isNotBlank();

        assertThat(info.walkingEstimate()).isNotBlank();
        Matcher matcher = WALKING_PATTERN.matcher(info.walkingEstimate());
        assertThat(matcher.find())
                .as("步行预估应包含分钟数与米数: %s", info.walkingEstimate())
                .isTrue();

        int minutes = Integer.parseInt(matcher.group(1));
        int meters = Integer.parseInt(matcher.group(2));
        // 车库就在传入坐标旁边，距离必须是个位数百米级
        assertThat(meters).isPositive().isLessThan(500);
        // 步速 80 m/min，且至少显示 1 分钟
        assertThat(minutes).isEqualTo(Math.max(1, (int) Math.round(meters / 80d)));
    }

    @Test
    @DisplayName("即便不给店名，也应纯靠坐标选出最近车库")
    void testRecommendByCoordinateOnly() {
        ParkingInfo info = parkingService.findNearbyParking(NEWTOWN_LAT, NEWTOWN_LNG, null, "zh");

        assertThat(info.hasParkingRecommendation()).isTrue();
        assertThat(info.parkingName()).isEqualTo(NEWTOWN_GARAGE);
    }

    @Test
    @DisplayName("坐标缺失时回落到店名匹配，而不是返回空建议")
    void testFallbackToPoiNameWhenCoordinateMissing() {
        ParkingInfo info = parkingService.findNearbyParking(0d, 0d, "Newtown", "zh");

        assertThat(info.hasParkingRecommendation()).isTrue();
        assertThat(info.parkingName()).isEqualTo(NEWTOWN_GARAGE);
    }

    @Test
    @DisplayName("lang=en 时步行预估与贴士应为纯英文")
    void testEnglishOutput() {
        ParkingInfo info = parkingService.findNearbyParking(NEWTOWN_LAT, NEWTOWN_LNG, "Newtown", "en");

        assertThat(info.walkingEstimate()).contains("min walk").doesNotContain("步行");
        assertThat(info.tip()).isNotBlank().doesNotContain("停车");
    }

    @Test
    @DisplayName("Haversine：1 个纬度约等于 111.19 公里，同点距离为 0")
    void testHaversineDistance() {
        // 地球半径 6371km 下，1 纬度 = 6371000 * π/180 ≈ 111194.9m
        assertThat(MockParkingService.haversineMeters(0d, 0d, 1d, 0d))
                .isCloseTo(111_194.9d, within(1d));
        assertThat(MockParkingService.haversineMeters(NEWTOWN_LAT, NEWTOWN_LNG, NEWTOWN_LAT, NEWTOWN_LNG))
                .isZero();
        // 对称性
        assertThat(MockParkingService.haversineMeters(-33.8688, 151.2093, NEWTOWN_LAT, NEWTOWN_LNG))
                .isCloseTo(MockParkingService.haversineMeters(NEWTOWN_LAT, NEWTOWN_LNG, -33.8688, 151.2093),
                        within(0.001d));
    }

    @Test
    @DisplayName("步行文案随语言切换，且分钟数不会出现 0")
    void testFormatWalking() {
        assertThat(MockParkingService.formatWalking(220d, LanguageEnum.ZH)).isEqualTo("步行约 3 分钟 (220m)");
        assertThat(MockParkingService.formatWalking(220d, LanguageEnum.EN)).isEqualTo("~3 mins walk (220m)");
        assertThat(MockParkingService.formatWalking(5d, LanguageEnum.ZH)).isEqualTo("步行约 1 分钟 (5m)");
    }

    @Test
    @DisplayName("(0,0) 与越界坐标视为无效，避免所有车库同分")
    void testCoordinateValidation() {
        assertThat(MockParkingService.isValidCoord(NEWTOWN_LAT, NEWTOWN_LNG)).isTrue();
        assertThat(MockParkingService.isValidCoord(0d, 0d)).isFalse();
        assertThat(MockParkingService.isValidCoord(-120d, 151d)).isFalse();
        assertThat(MockParkingService.isValidCoord(-33d, 200d)).isFalse();
    }
}
