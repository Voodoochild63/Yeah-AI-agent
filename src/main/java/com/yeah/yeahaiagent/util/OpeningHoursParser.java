package com.yeah.yeahaiagent.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Serper / Google Maps 营业时间文本收成当天的时间窗。
 */
public final class OpeningHoursParser {

    public record Window(LocalTime open, LocalTime close, boolean overnight) {
        public boolean contains(LocalTime time) {
            if (overnight) {
                return !time.isBefore(open) || time.isBefore(close);
            }
            return !time.isBefore(open) && time.isBefore(close);
        }

        public int minutesUntilClose(LocalTime time) {
            int closeMin = toMinutes(close) + (overnight ? 24 * 60 : 0);
            int arrival = toMinutes(time);
            if (overnight && time.isBefore(open)) {
                arrival += 24 * 60;
            }
            return closeMin - arrival;
        }
    }

    public record DayHours(boolean known, boolean open24h, boolean closedAllDay, List<Window> windows) {
        public static DayHours unknown() {
            return new DayHours(false, false, false, List.of());
        }

        public static DayHours closed() {
            return new DayHours(true, false, true, List.of());
        }

        public static DayHours open24() {
            return new DayHours(true, true, false, List.of(
                    new Window(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true)));
        }
    }

    private static final Pattern CLOCK = Pattern.compile(
            "(?<h>\\d{1,2})(?::(?<m>\\d{2}))?\\s*(?<ap>AM|PM|A\\.M\\.|P\\.M\\.|上午|下午)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile(
            "(?<open>\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM|A\\.M\\.|P\\.M\\.|上午|下午)?)\\s*"
                    + "(?:[-–—~]|to|至)\\s*"
                    + "(?<close>\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM|A\\.M\\.|P\\.M\\.|上午|下午)?)",
            Pattern.CASE_INSENSITIVE);

    private OpeningHoursParser() {
    }

    public static String normalizeSpaces(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replace('\u202f', ' ')
                .replace('\u00a0', ' ')
                .replace('\u2007', ' ')
                .replace('\u2009', ' ')
                .replace('\u200a', ' ')
                .replace('−', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static DayHours forDay(List<String> lines, DayOfWeek day) {
        if (lines == null || lines.isEmpty() || day == null) {
            return DayHours.unknown();
        }
        String line = findDayLine(lines, day);
        if (line == null) {
            return DayHours.unknown();
        }
        return parseLine(line);
    }

    public static DayHours parseLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return DayHours.unknown();
        }
        String line = stripDayPrefix(normalizeSpaces(raw));
        if (line.isBlank()) {
            return DayHours.unknown();
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("24 hour") || lower.contains("24-hour") || lower.contains("24小时")
                || lower.contains("open 24")) {
            return DayHours.open24();
        }
        if (lower.equals("closed") || lower.contains("closed") || lower.contains("休息")
                || lower.contains("不营业") || lower.contains("打烊")) {
            if (!RANGE.matcher(line).find()) {
                return DayHours.closed();
            }
        }
        List<Window> windows = new ArrayList<>();
        Matcher matcher = RANGE.matcher(line);
        while (matcher.find()) {
            LocalTime open = parseClock(matcher.group("open"));
            LocalTime close = parseClock(matcher.group("close"));
            if (open == null || close == null) {
                continue;
            }
            windows.add(new Window(open, close, !close.isAfter(open)));
        }
        if (windows.isEmpty()) {
            return DayHours.unknown();
        }
        return new DayHours(true, false, false, List.copyOf(windows));
    }

    public static LocalTime parseClock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = CLOCK.matcher(normalizeSpaces(raw));
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group("h"));
        int minute = matcher.group("m") == null ? 0 : Integer.parseInt(matcher.group("m"));
        if (minute > 59) {
            return null;
        }
        String ap = matcher.group("ap");
        if (ap != null) {
            String marker = ap.replace(".", "").toLowerCase(Locale.ROOT);
            boolean pm = marker.equals("pm") || marker.equals("下午");
            boolean am = marker.equals("am") || marker.equals("上午");
            if (pm && hour < 12) {
                hour += 12;
            } else if (am && hour == 12) {
                hour = 0;
            }
        }
        if (hour > 23) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    public static String formatClock(LocalTime time) {
        if (time == null) {
            return null;
        }
        return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    /**
     * 行程顺推用：禁止 {@link LocalTime#plusMinutes(long)} 跨日回绕把 21:24 变成下午。
     */
    public static LocalTime plusMinutesClamped(LocalTime time, int minutes) {
        if (time == null) {
            return null;
        }
        int total = time.toSecondOfDay() / 60 + minutes;
        if (total < 0) {
            total = 0;
        }
        int endOfDay = 23 * 60 + 59;
        if (total > endOfDay) {
            total = endOfDay;
        }
        return LocalTime.of(total / 60, total % 60);
    }

    public static String friendlyText(DayHours hours, List<String> lines, boolean zh) {
        if (hours != null && hours.open24h()) {
            return zh ? "全天开放" : "Open 24 hours";
        }
        if (hours != null && hours.closedAllDay()) {
            return zh ? "今日休息" : "Closed today";
        }
        if (hours != null && hours.known() && hours.windows() != null && !hours.windows().isEmpty()) {
            String range = hours.windows().stream()
                    .map(window -> formatClock(window.open()) + "–" + formatClock(window.close()))
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return zh ? "今日 " + range : "Today " + range;
        }
        if (lines != null && !lines.isEmpty()) {
            return String.join(" · ", lines.stream().limit(2).toList());
        }
        return null;
    }

    static String findDayLine(List<String> lines, DayOfWeek day) {
        List<String> aliases = aliases(day);
        String fallback = null;
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
        String normalized = normalizeSpaces(raw).toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
                if (normalized.startsWith(alias)
                        || normalized.contains(alias + ":")
                        || normalized.contains(alias + " ")) {
                    return normalizeSpaces(raw);
                }
            }
            if (isUniversalHoursLine(normalized)) {
                return normalizeSpaces(raw);
            }
            if (fallback == null && !looksLikeDayPrefixed(normalized)) {
                fallback = normalizeSpaces(raw);
            }
        }
        return lines.size() == 1 ? normalizeSpaces(lines.get(0)) : fallback;
    }

    static boolean isUniversalHoursLine(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("mon-sun")
                || normalized.contains("mon–sun")
                || normalized.contains("mon — sun")
                || normalized.contains("monday-sunday")
                || normalized.contains("monday–sunday")
                || normalized.contains("daily")
                || normalized.contains("every day")
                || normalized.contains("everyday")
                || normalized.contains("每天")
                || normalized.contains("每日");
    }

    private static boolean looksLikeDayPrefixed(String normalized) {
        for (DayOfWeek weekDay : DayOfWeek.values()) {
            for (String alias : aliases(weekDay)) {
                if (normalized.startsWith(alias) || normalized.contains(alias + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripDayPrefix(String raw) {
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon > 16) {
            return raw;
        }
        String head = normalizeSpaces(raw.substring(0, colon)).toLowerCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            for (String alias : aliases(day)) {
                if (head.equals(alias) || head.startsWith(alias)) {
                    return raw.substring(colon + 1);
                }
            }
        }
        return raw;
    }

    private static List<String> aliases(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> List.of("monday", "mon", "周一", "星期一");
            case TUESDAY -> List.of("tuesday", "tue", "tues", "周二", "星期二");
            case WEDNESDAY -> List.of("wednesday", "wed", "周三", "星期三");
            case THURSDAY -> List.of("thursday", "thu", "thur", "thurs", "周四", "星期四");
            case FRIDAY -> List.of("friday", "fri", "周五", "星期五");
            case SATURDAY -> List.of("saturday", "sat", "周六", "星期六");
            case SUNDAY -> List.of("sunday", "sun", "周日", "星期日", "星期天");
        };
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
