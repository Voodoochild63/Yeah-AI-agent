package com.yeah.yeahaiagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("规划请求指纹")
class ItineraryRequestFingerprintTest {

    @Test
    @DisplayName("相同街区、主题与时段得到同一把缓存键")
    void sameSuburbThemeTimeShareKey() {
        String first = ItineraryRequestFingerprint.key("帮我安排 Bondi 海边半日游", "zh", null);
        String second = ItineraryRequestFingerprint.key("Bondi 海边半日游", "zh", null);
        assertThat(first).isEqualTo(second);
        assertThat(first).contains("bondi").contains("coastal").contains("half-day");
    }

    @Test
    @DisplayName("一日游与半日游的 timeOfDay 不同")
    void fullDayDiffersFromHalfDay() {
        String half = ItineraryRequestFingerprint.key("Bondi 半日游", "zh", null);
        String full = ItineraryRequestFingerprint.key("Bondi 一日游", "zh", null);
        assertThat(half).contains("half-day");
        assertThat(full).contains("full-day");
        assertThat(half).isNotEqualTo(full);
    }
}
