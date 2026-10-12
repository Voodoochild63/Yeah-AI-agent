package com.yeah.yeahaiagent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("GeoDistanceUtils - Haversine")
class GeoDistanceUtilsTest {

    private static final double OPERA_LAT = -33.8568;
    private static final double OPERA_LNG = 151.2153;
    private static final double BONDI_LAT = -33.8915;
    private static final double BONDI_LNG = 151.2767;

    @Test
    @DisplayName("Opera House 到 Bondi 约 7km，对称")
    void operaToBondiIsAboutSevenKm() {
        double ab = GeoDistanceUtils.calculateDistanceKm(OPERA_LAT, OPERA_LNG, BONDI_LAT, BONDI_LNG);
        double ba = GeoDistanceUtils.calculateDistanceKm(BONDI_LAT, BONDI_LNG, OPERA_LAT, OPERA_LNG);
        assertThat(ab).isCloseTo(7.0d, within(1.5d));
        assertThat(ab).isCloseTo(ba, within(0.0001d));
        assertThat(GeoDistanceUtils.calculateDistanceMeters(OPERA_LAT, OPERA_LNG, BONDI_LAT, BONDI_LNG))
                .isCloseTo(ab * 1000d, within(1d));
    }

    @Test
    @DisplayName("Brickfields → Observatory Hill 直线约 3km，不是 690m")
    void brickfieldsToObservatoryHillIsAboutThreeKm() {
        double km = GeoDistanceUtils.calculateDistanceKm(-33.8848, 151.1996, -33.8593, 151.2048);
        assertThat(km).isBetween(2.5d, 5.0d);
        assertThat(GeoDistanceUtils.calculateDistanceMeters(-33.8848, 151.1996, -33.8593, 151.2048))
                .isGreaterThan(2500d);
    }

    @Test
    @DisplayName("同一点距离为 0；零坐标非法")
    void samePointAndInvalidOrigin() {
        assertThat(GeoDistanceUtils.calculateDistanceKm(OPERA_LAT, OPERA_LNG, OPERA_LAT, OPERA_LNG))
                .isCloseTo(0d, within(1e-9));
        assertThat(GeoDistanceUtils.isValidCoord(OPERA_LAT, OPERA_LNG)).isTrue();
        assertThat(GeoDistanceUtils.isValidCoord(0d, 0d)).isFalse();
        assertThat(GeoDistanceUtils.isValidCoord(-91d, 151d)).isFalse();
    }
}
