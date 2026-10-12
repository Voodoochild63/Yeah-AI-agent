package com.yeah.yeahaiagent.util;

import com.yeah.yeahaiagent.util.OpeningHoursParser.DayHours;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpeningHoursParser")
class OpeningHoursParserTest {

    @Test
    @DisplayName("解析 Serper 的 Monday: 8:00 AM – 3:00 PM")
    void parseSerperWeekdayLine() {
        DayHours hours = OpeningHoursParser.forDay(
                List.of("Monday: 8:00 AM – 3:00 PM", "Tuesday: Closed"),
                DayOfWeek.MONDAY);
        assertThat(hours.known()).isTrue();
        assertThat(hours.windows()).hasSize(1);
        assertThat(hours.windows().get(0).open()).isEqualTo(LocalTime.of(8, 0));
        assertThat(hours.windows().get(0).close()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("当天 Closed / 24 hours / 跨夜")
    void closedOpen24AndOvernight() {
        assertThat(OpeningHoursParser.forDay(List.of("Saturday: Closed"), DayOfWeek.SATURDAY).closedAllDay())
                .isTrue();
        assertThat(OpeningHoursParser.forDay(List.of("Sunday: Open 24 hours"), DayOfWeek.SUNDAY).open24h())
                .isTrue();
        DayHours night = OpeningHoursParser.parseLine("Thursday: 10:00 PM – 2:00 AM");
        assertThat(night.windows().get(0).overnight()).isTrue();
        assertThat(night.windows().get(0).contains(LocalTime.of(23, 0))).isTrue();
        assertThat(night.windows().get(0).contains(LocalTime.of(1, 0))).isTrue();
        assertThat(night.windows().get(0).contains(LocalTime.of(9, 0))).isFalse();
    }

    @Test
    @DisplayName("对象风格 monday 键也能对上周四")
    void mapStyleKeys() {
        DayHours hours = OpeningHoursParser.forDay(
                List.of("Thursday: 7 AM-11 AM, 5:00 PM – 9:00 PM"),
                DayOfWeek.THURSDAY);
        assertThat(hours.windows()).hasSize(2);
        assertThat(hours.windows().get(1).open()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("Mon-Sun 一行对所有星期生效，窄空格也能解析下午")
    void monSunAndNarrowSpace() {
        DayHours hours = OpeningHoursParser.forDay(
                List.of("Mon-Sun: 8:00 AM - 4:00 PM"),
                DayOfWeek.THURSDAY);
        assertThat(hours.windows().get(0).open()).isEqualTo(LocalTime.of(8, 0));
        assertThat(hours.windows().get(0).close()).isEqualTo(LocalTime.of(16, 0));
        DayHours maps = OpeningHoursParser.parseLine("Thursday: 10\u202fAM–5\u202fPM");
        assertThat(maps.windows().get(0).open()).isEqualTo(LocalTime.of(10, 0));
        assertThat(maps.windows().get(0).close()).isEqualTo(LocalTime.of(17, 0));
    }
}
