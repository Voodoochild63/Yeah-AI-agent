package com.yeah.yeahaiagent.service;

import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.TransitModeOption;
import com.yeah.yeahaiagent.model.dto.TransitOptions;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 用节点真实坐标（Serper / 地标）算直线距离，再按 1.25 路网系数推三种出行预估。
 */
@Component
@Slf4j
public class ItineraryTransitPlanner {

    static final double WALK_MAX_KM = 1.2d;
    static final double ROAD_FACTOR = 1.25d;
    static final double IMPLAUSIBLE_STRAIGHT_KM = 50d;
    static final double SHORT_LEG_KM = 20d;
    static final int MAX_MINUTES_UNDER_20KM = 120;
    static final int MAX_TRANSIT_STOPS = 20;
    static final int FALLBACK_WALK_MIN = 12;
    static final int FALLBACK_DRIVE_MIN = 8;
    static final int FALLBACK_TRANSIT_MIN = 15;
    static final int FALLBACK_METERS = 900;
    static final int FALLBACK_STOPS = 2;

    private static final double WALK_KMH = 4.5d;
    private static final double DRIVE_KMH = 25d;
    private static final double TRANSIT_KMH = 20d;
    private static final int DRIVE_LIGHT_BUFFER_MIN = 2;
    private static final int TRANSIT_WAIT_MIN = 5;

    public static final String MODE_WALKING = "WALKING";
    public static final String MODE_DRIVING = "DRIVING";
    public static final String MODE_TRANSIT = "TRANSIT";

    private final SydneyPlaceLocator placeLocator;

    public ItineraryTransitPlanner(SydneyPlaceLocator placeLocator) {
        this.placeLocator = placeLocator;
    }

    public void rememberPlaces(List<RealPlaceDto> places) {
        placeLocator.remember(places);
    }

    public void apply(ItineraryPlan plan, LanguageEnum language) {
        List<ItineraryNode> schedule = plan == null ? null : plan.getSchedule();
        if (schedule == null || schedule.size() < 2) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        bindCoordinates(schedule);
        for (int i = 0; i < schedule.size() - 1; i++) {
            ItineraryNode from = schedule.get(i);
            ItineraryNode to = schedule.get(i + 1);
            if (from == null || to == null) {
                continue;
            }
            Optional<Double> straightKm = measureStraightKm(from, to);
            if (straightKm.isEmpty()) {
                log.warn("skip transit estimate, no coordinates. from={} to={}",
                        from.getPoiName(), to.getPoiName());
                continue;
            }
            double km = straightKm.get();
            TransitOptions options = estimateOptions(km, lang);
            sanitize(options, lang);
            String mode = km <= WALK_MAX_KM && plausibleDistance(km)
                    ? MODE_WALKING : MODE_DRIVING;
            if (!plausibleDistance(km)) {
                mode = MODE_WALKING;
            }
            from.setTransitOptions(options);
            from.setSelectedMode(mode);
            from.setTransitToNext(formatSelected(options, mode, lang));
            log.info("leg modes ready. from={} to={} straightKm={} roadKm={} mode={}",
                    from.getPoiName(), to.getPoiName(),
                    String.format(Locale.ROOT, "%.2f", km),
                    String.format(Locale.ROOT, "%.2f", km * ROAD_FACTOR),
                    mode);
        }
    }

    private void bindCoordinates(List<ItineraryNode> schedule) {
        for (ItineraryNode node : schedule) {
            if (node == null || node.getPoiName() == null) {
                continue;
            }
            if (hasCoords(node)) {
                placeLocator.remember(node.getPoiName(), node.getLatitude(), node.getLongitude(), "node");
                continue;
            }
            placeLocator.locate(node.getPoiName()).ifPresent(point -> {
                node.setLatitude(point.lat());
                node.setLongitude(point.lng());
            });
        }
    }

    static TransitOptions estimateOptions(double straightKm, LanguageEnum language) {
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        if (!plausibleDistance(straightKm)) {
            log.warn("implausible transit distance {}km, falling back to a short walk", straightKm);
            return fallbackOptions(lang);
        }
        double km = Math.max(0d, straightKm);
        double roadKm = km * ROAD_FACTOR;
        int roadMeters = (int) Math.round(roadKm * 1000d);
        int walkMin = clampHopMinutes(Math.max(1, (int) Math.round(roadKm / WALK_KMH * 60d)), roadMeters);
        int driveMin = clampHopMinutes(
                Math.max(2, (int) Math.round(roadKm / DRIVE_KMH * 60d) + DRIVE_LIGHT_BUFFER_MIN), roadMeters);
        int transitMin = clampHopMinutes(
                Math.max(6, TRANSIT_WAIT_MIN + (int) Math.round(roadKm / TRANSIT_KMH * 60d)), roadMeters);
        int stops = clampTransitStops((int) Math.round(roadKm / 0.8d));
        TransitOptions options = new TransitOptions(
                new TransitModeOption(minutesText(walkMin, lang), distanceText(roadMeters), walkMin, roadMeters),
                new TransitModeOption(minutesText(driveMin, lang), distanceText(roadMeters), driveMin, roadMeters),
                new TransitModeOption(minutesText(transitMin, lang), transitDistanceText(stops, lang), transitMin, roadMeters)
        );
        sanitize(options, lang);
        return options;
    }

    static void sanitize(TransitOptions options, LanguageEnum language) {
        if (options == null) {
            return;
        }
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        options.setWalking(sanitizeOption(options.getWalking(), lang, false));
        options.setDriving(sanitizeOption(options.getDriving(), lang, false));
        options.setTransit(sanitizeOption(options.getTransit(), lang, true));
    }

    static TransitModeOption sanitizeOption(TransitModeOption option, LanguageEnum language, boolean transit) {
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        if (option == null) {
            return transit
                    ? new TransitModeOption(minutesText(FALLBACK_TRANSIT_MIN, lang),
                    transitDistanceText(FALLBACK_STOPS, lang), FALLBACK_TRANSIT_MIN, FALLBACK_METERS)
                    : new TransitModeOption(minutesText(FALLBACK_WALK_MIN, lang),
                    distanceText(FALLBACK_METERS), FALLBACK_WALK_MIN, FALLBACK_METERS);
        }
        Integer meters = option.getDistanceMeters();
        int minutes = clampHopMinutes(option.getDurationMinutes(), meters);
        int safeMeters = meters == null || meters < 0 || meters > 50_000 ? FALLBACK_METERS : meters;
        option.setDurationMinutes(minutes);
        option.setDurationText(minutesText(minutes, lang));
        option.setDistanceMeters(safeMeters);
        if (transit) {
            int stops = clampTransitStops(inferStops(option.getDistanceText(), safeMeters));
            option.setDistanceText(transitDistanceText(stops, lang));
        } else {
            option.setDistanceText(distanceText(safeMeters));
        }
        return option;
    }

    static int clampHopMinutes(Integer minutes, Integer meters) {
        if (minutes == null || minutes < 0 || minutes > 1000) {
            return FALLBACK_WALK_MIN;
        }
        boolean shortLeg = meters == null || meters < SHORT_LEG_KM * 1000d;
        int cap = shortLeg ? MAX_MINUTES_UNDER_20KM : MAX_MINUTES_UNDER_20KM;
        return Math.min(Math.max(minutes, 1), cap);
    }

    static int clampTransitStops(int stops) {
        if (stops < 0 || stops > 40) {
            return FALLBACK_STOPS;
        }
        return Math.max(1, Math.min(stops, MAX_TRANSIT_STOPS));
    }

    static boolean plausibleDistance(double km) {
        return !Double.isNaN(km) && !Double.isInfinite(km) && km >= 0d && km <= IMPLAUSIBLE_STRAIGHT_KM;
    }

    private static TransitOptions fallbackOptions(LanguageEnum language) {
        LanguageEnum lang = language == null ? LanguageEnum.ZH : language;
        return new TransitOptions(
                new TransitModeOption(minutesText(FALLBACK_WALK_MIN, lang),
                        distanceText(FALLBACK_METERS), FALLBACK_WALK_MIN, FALLBACK_METERS),
                new TransitModeOption(minutesText(FALLBACK_DRIVE_MIN, lang),
                        distanceText(FALLBACK_METERS), FALLBACK_DRIVE_MIN, FALLBACK_METERS),
                new TransitModeOption(minutesText(FALLBACK_TRANSIT_MIN, lang),
                        transitDistanceText(FALLBACK_STOPS, lang), FALLBACK_TRANSIT_MIN, FALLBACK_METERS)
        );
    }

    private static int inferStops(String distanceText, int meters) {
        if (distanceText != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(distanceText);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return Math.max(1, (int) Math.round(meters / 800d));
    }

    static String formatSelected(TransitOptions options, String mode, LanguageEnum language) {
        TransitModeOption option = pick(options, mode);
        if (option == null) {
            return language.isZh() ? "前往下一站" : "To the next stop";
        }
        String verb = switch (mode == null ? MODE_WALKING : mode) {
            case MODE_DRIVING -> language.isZh() ? "驾车" : "Drive";
            case MODE_TRANSIT -> language.isZh() ? "公交" : "Transit";
            default -> language.isZh() ? "步行" : "Walk";
        };
        return verb + " " + option.getDurationText() + " · " + option.getDistanceText();
    }

    static TransitModeOption pick(TransitOptions options, String mode) {
        if (options == null) {
            return null;
        }
        if (MODE_DRIVING.equals(mode)) {
            return options.getDriving();
        }
        if (MODE_TRANSIT.equals(mode)) {
            return options.getTransit();
        }
        return options.getWalking();
    }

    private Optional<Double> measureStraightKm(ItineraryNode from, ItineraryNode to) {
        Optional<SydneyPlaceLocator.GeoPoint> a = coordsOf(from);
        Optional<SydneyPlaceLocator.GeoPoint> b = coordsOf(to);
        if (a.isEmpty() || b.isEmpty()) {
            return inferBySuburb(from.getPoiName(), to.getPoiName());
        }
        SydneyPlaceLocator.GeoPoint p = a.get();
        SydneyPlaceLocator.GeoPoint q = b.get();
        double km = GeoDistanceUtils.calculateDistanceKm(p.lat(), p.lng(), q.lat(), q.lng());
        if (km < 0.05d && !sameName(from.getPoiName(), to.getPoiName())) {
            return inferBySuburb(from.getPoiName(), to.getPoiName());
        }
        return Optional.of(km);
    }

    private Optional<SydneyPlaceLocator.GeoPoint> coordsOf(ItineraryNode node) {
        if (hasCoords(node)) {
            return Optional.of(new SydneyPlaceLocator.GeoPoint(node.getLatitude(), node.getLongitude(), "node"));
        }
        return placeLocator.locate(node.getPoiName());
    }

    private static boolean hasCoords(ItineraryNode node) {
        return node != null
                && node.getLatitude() != null
                && node.getLongitude() != null
                && GeoDistanceUtils.isValidCoord(node.getLatitude(), node.getLongitude());
    }

    private static boolean sameName(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private Optional<Double> inferBySuburb(String fromName, String toName) {
        String fromSuburb = SydneyPlaceLocator.guessSuburb(fromName);
        String toSuburb = SydneyPlaceLocator.guessSuburb(toName);
        if ("Sydney".equals(fromSuburb) || "Sydney".equals(toSuburb)
                || fromSuburb.equalsIgnoreCase(toSuburb)) {
            return Optional.empty();
        }
        Optional<SydneyPlaceLocator.GeoPoint> from = placeLocator.locate(fromName);
        Optional<SydneyPlaceLocator.GeoPoint> to = placeLocator.locate(toName);
        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(GeoDistanceUtils.calculateDistanceKm(
                from.get().lat(), from.get().lng(), to.get().lat(), to.get().lng()));
    }

    private static String minutesText(int minutes, LanguageEnum language) {
        return language.isZh() ? minutes + "分钟" : minutes + " min";
    }

    private static String distanceText(int meters) {
        if (meters < 1000) {
            return Math.max(meters, 1) + "m";
        }
        return String.format(Locale.ROOT, "%.1fkm", meters / 1000d);
    }

    private static String transitDistanceText(int stops, LanguageEnum language) {
        if (language.isZh()) {
            return "公交" + stops + "站";
        }
        return stops == 1 ? "1 stop" : stops + " stops";
    }
}
