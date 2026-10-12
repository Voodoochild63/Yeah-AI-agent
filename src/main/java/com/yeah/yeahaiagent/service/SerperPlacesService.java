package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.yeah.yeahaiagent.config.SerperProperties;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.resilience.UpstreamApiGuard;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import com.yeah.yeahaiagent.util.OpeningHoursParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serper.dev Places：抓真实 Google Maps 店铺，并做 4.0 星 / 20 条评价硬过滤。
 */
@Service
@Slf4j
public class SerperPlacesService {

    private static final double MIN_RATING = 4.0d;
    private static final int MIN_RATING_COUNT = 20;
    private static final int MAX_RESULTS = 10;

    private final SerperProperties properties;
    private final RestClient restClient;
    private final UpstreamApiGuard upstreamApiGuard;

    public SerperPlacesService(SerperProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, null);
    }

    @Autowired
    public SerperPlacesService(SerperProperties properties,
                               RestClient.Builder restClientBuilder,
                               ObjectProvider<UpstreamApiGuard> upstreamApiGuard) {
        this.properties = properties;
        this.upstreamApiGuard = upstreamApiGuard == null ? null : upstreamApiGuard.getIfAvailable();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(12));
        String baseUrl = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? "https://google.serper.dev"
                : properties.getBaseUrl().trim();
        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public List<RealPlaceDto> searchVerifiedPlaces(String query) {
        return searchPlaces(query, true);
    }

    /**
     * 落日观景检索：要坐标、不要 20 条评价门槛（海角 lookout 常常评分数不够）。
     */
    public List<RealPlaceDto> searchScenicPlaces(String query) {
        return searchPlaces(query, false);
    }

    private List<RealPlaceDto> searchPlaces(String query, boolean strictReviews) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("serper.api-key 未配置，跳过物理地点检索");
            return List.of();
        }
        try {
            PlacesResponse response = fetchPlaces("/maps", query, apiKey);
            if (response == null || response.places() == null || response.places().isEmpty()) {
                response = fetchPlaces("/places", query, apiKey);
            }
            if (response == null || response.places() == null) {
                return List.of();
            }
            return response.places().stream()
                    .filter(place -> place != null && place.title() != null && !place.title().isBlank())
                    .filter(place -> place.rating() == null || place.rating() >= MIN_RATING)
                    .filter(place -> !strictReviews || resolveRatingCount(place) >= MIN_RATING_COUNT)
                    .filter(place -> {
                        Double lat = resolveLatitude(place);
                        Double lng = resolveLongitude(place);
                        return lat != null && lng != null && GeoDistanceUtils.isValidCoord(lat, lng);
                    })
                    .sorted(Comparator.comparing(SerperPlace::rating, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_RESULTS)
                    .map(SerperPlacesService::toDto)
                    .toList();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Serper places 检索失败。query={}", query, ex);
            return List.of();
        }
    }

    public List<RealPlaceDto> searchAndMerge(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        Map<String, RealPlaceDto> unique = new LinkedHashMap<>();
        for (String query : queries) {
            for (RealPlaceDto place : searchVerifiedPlaces(query)) {
                unique.putIfAbsent(dedupeKey(place), place);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private PlacesResponse fetchPlaces(String path, String query, String apiKey) {
        if (upstreamApiGuard != null) {
            upstreamApiGuard.acquireSerper();
        }
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-KEY", apiKey)
                    .body(Map.of("q", query.trim(), "gl", "au", "hl", "en"))
                    .retrieve()
                    .body(PlacesResponse.class);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Serper {} 检索失败。query={}", path, query, ex);
            return null;
        }
    }

    private static RealPlaceDto toDto(SerperPlace place) {
        List<String> hours = flattenOpeningHours(firstHoursNode(place));
        RealPlaceDto dto = new RealPlaceDto(
                place.title().trim(),
                place.address(),
                place.rating(),
                resolveRatingCount(place),
                firstNonBlank(place.category(), place.type()),
                resolveLatitude(place),
                resolveLongitude(place),
                hours,
                hours.isEmpty() ? null : String.join(" · ", hours));
        if (!hours.isEmpty()) {
            log.info("serper hours captured. title={} lines={}", dto.getTitle(), hours.size());
        }
        return dto;
    }

    private static JsonNode firstHoursNode(SerperPlace place) {
        if (notEmpty(place.openingHours())) {
            return place.openingHours();
        }
        return place.hours();
    }

    private static boolean notEmpty(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode()
                && !(node.isObject() && node.isEmpty())
                && !(node.isArray() && node.isEmpty())
                && !(node.isTextual() && node.asText().isBlank());
    }

    static List<String> flattenOpeningHours(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        collectHours(node, lines);
        return List.copyOf(lines);
    }

    private static void collectHours(JsonNode node, List<String> lines) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            addHoursLine(lines, node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectHours(item, lines));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode weekdayText = node.get("weekday_text") != null ? node.get("weekday_text") : node.get("weekdayText");
        if (weekdayText != null && weekdayText.isArray()) {
            collectHours(weekdayText, lines);
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String day = entry.getKey() == null ? "" : entry.getKey().trim();
            JsonNode value = entry.getValue();
            if (value != null && (value.isObject() || value.isArray())) {
                collectHours(value, lines);
                return;
            }
            String text = value == null || value.isNull() ? "" : value.asText();
            if (day.isBlank() || isMetaHoursKey(day)) {
                addHoursLine(lines, text);
            } else {
                addHoursLine(lines, capitalizeDay(day) + ": " + text);
            }
        });
    }

    private static boolean isMetaHoursKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("open_now") || lower.equals("openNow") || lower.equals("periods");
    }

    private static void addHoursLine(List<String> lines, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = OpeningHoursParser.normalizeSpaces(raw);
        if (normalized.isBlank()) {
            return;
        }
        lines.add(normalized);
    }

    private static String capitalizeDay(String day) {
        if (day.isEmpty()) {
            return day;
        }
        return day.substring(0, 1).toUpperCase(Locale.ROOT) + day.substring(1);
    }

    private static Double resolveLatitude(SerperPlace place) {
        if (place.latitude() != null) {
            return place.latitude();
        }
        return place.gpsCoordinates() == null ? null : place.gpsCoordinates().latitude();
    }

    private static Double resolveLongitude(SerperPlace place) {
        if (place.longitude() != null) {
            return place.longitude();
        }
        return place.gpsCoordinates() == null ? null : place.gpsCoordinates().longitude();
    }

    private static int resolveRatingCount(SerperPlace place) {
        if (place.ratingCount() != null) {
            return place.ratingCount();
        }
        return place.reviews() == null ? 0 : place.reviews();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String dedupeKey(RealPlaceDto place) {
        String title = place.getTitle() == null ? "" : place.getTitle().toLowerCase(Locale.ROOT).trim();
        String address = place.getAddress() == null ? "" : place.getAddress().toLowerCase(Locale.ROOT).trim();
        return title + "|" + address;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlacesResponse(List<SerperPlace> places) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SerperPlace(
            String title,
            String address,
            Double rating,
            Integer ratingCount,
            @JsonAlias("reviews") Integer reviews,
            String category,
            String type,
            @JsonAlias({"lat"}) Double latitude,
            @JsonAlias({"lng", "lon"}) Double longitude,
            @JsonAlias({"gpsCoordinates", "gps_coordinates"}) GpsCoordinates gpsCoordinates,
            @JsonAlias({"openingHours", "opening_hours", "openHours", "open_hours",
                    "operatingHours", "regularOpeningHours", "currentOpeningHours"}) JsonNode openingHours,
            @JsonAlias({"hours"}) JsonNode hours
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GpsCoordinates(
            @JsonAlias({"lat"}) Double latitude,
            @JsonAlias({"lng", "lon"}) Double longitude
    ) {
    }
}
