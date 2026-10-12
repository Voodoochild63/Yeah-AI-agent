package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.util.GeoDistanceUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把店名落到悉尼附近的经纬度。
 * 优先级：Serper 已知点 → 地标表 → Open-Meteo（拒绝笼统的 Sydney）→ 街区质心。
 */
@Component
@Slf4j
public class SydneyPlaceLocator {

    public record GeoPoint(double lat, double lng, String source) {
    }

    private static final Map<String, GeoPoint> SUBURB_CENTROIDS = suburbCentroids();
    private static final Map<String, GeoPoint> LANDMARKS = landmarks();

    private final RestClient restClient;
    private final ConcurrentHashMap<String, GeoPoint> knownPlaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<GeoPoint>> cache = new ConcurrentHashMap<>();

    public SydneyPlaceLocator(RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = restClientBuilder.clone()
                .baseUrl("https://geocoding-api.open-meteo.com")
                .requestFactory(factory)
                .build();
    }

    /** 单测用：只走地标 / 街区质心 / remember，不打外部地理编码。 */
    SydneyPlaceLocator() {
        this.restClient = null;
    }

    public void remember(List<RealPlaceDto> places) {
        if (places == null) {
            return;
        }
        for (RealPlaceDto place : places) {
            if (place == null || place.getTitle() == null || place.getTitle().isBlank()) {
                continue;
            }
            if (place.getLatitude() == null || place.getLongitude() == null
                    || !GeoDistanceUtils.isValidCoord(place.getLatitude(), place.getLongitude())) {
                continue;
            }
            remember(place.getTitle(), place.getLatitude(), place.getLongitude(), "serper");
        }
    }

    public void remember(String name, double lat, double lng, String source) {
        if (name == null || name.isBlank() || !GeoDistanceUtils.isValidCoord(lat, lng)) {
            return;
        }
        GeoPoint point = new GeoPoint(lat, lng, source);
        knownPlaces.put(name.trim().toLowerCase(Locale.ROOT), point);
        cache.put(name.trim().toLowerCase(Locale.ROOT), Optional.of(point));
    }

    public Optional<GeoPoint> locate(String poiName) {
        if (poiName == null || poiName.isBlank()) {
            return Optional.empty();
        }
        String key = poiName.trim().toLowerCase(Locale.ROOT);
        Optional<GeoPoint> remembered = matchKnown(key);
        if (remembered.isPresent()) {
            return remembered;
        }
        return cache.computeIfAbsent(key, ignored -> resolve(poiName.trim()));
    }

    private Optional<GeoPoint> matchKnown(String key) {
        GeoPoint exact = knownPlaces.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, GeoPoint> entry : knownPlaces.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<GeoPoint> resolve(String poiName) {
        Optional<GeoPoint> landmark = matchTable(poiName, LANDMARKS);
        if (landmark.isPresent()) {
            return landmark;
        }
        Optional<GeoPoint> remote = geocode(poiName);
        if (remote.isPresent()) {
            return remote;
        }
        String suburb = guessSuburb(poiName);
        if (!"Sydney".equals(suburb)) {
            GeoPoint centroid = SUBURB_CENTROIDS.get(suburb.toLowerCase(Locale.ROOT));
            if (centroid != null) {
                return Optional.of(centroid);
            }
        }
        return Optional.empty();
    }

    private Optional<GeoPoint> geocode(String query) {
        if (restClient == null) {
            return Optional.empty();
        }
        try {
            GeocodingResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search")
                            .queryParam("name", query)
                            .queryParam("count", 3)
                            .queryParam("language", "en")
                            .queryParam("country", "AU")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GeocodingResponse.class);
            if (response == null || response.results() == null) {
                return Optional.empty();
            }
            for (GeocodingHit hit : response.results()) {
                if (hit == null || !GeoDistanceUtils.isValidCoord(hit.latitude(), hit.longitude())) {
                    continue;
                }
                if (isGenericSydney(hit, query)) {
                    continue;
                }
                return Optional.of(new GeoPoint(hit.latitude(), hit.longitude(), "geocode"));
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("geocode failed. query={}", query, ex);
            return Optional.empty();
        }
    }

    private static boolean isGenericSydney(GeocodingHit hit, String query) {
        String name = hit.name() == null ? "" : hit.name().trim();
        String q = query.toLowerCase(Locale.ROOT);
        boolean queryIsSpecific = q.contains("park") || q.contains("street") || q.contains(" ")
                || q.contains("gallery") || q.contains("cafe") || q.contains("coffee");
        return queryIsSpecific && "sydney".equalsIgnoreCase(name);
    }

    static String guessSuburb(String poiName) {
        String normalized = poiName.toLowerCase(Locale.ROOT);
        for (String suburb : SUBURB_CENTROIDS.keySet()) {
            if (normalized.contains(suburb)) {
                return capitalizeSuburb(suburb);
            }
        }
        return "Sydney";
    }

    private static Optional<GeoPoint> matchTable(String poiName, Map<String, GeoPoint> table) {
        String normalized = poiName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, GeoPoint> entry : table.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static String capitalizeSuburb(String suburb) {
        if ("the rocks".equals(suburb)) {
            return "The Rocks";
        }
        if ("surry hills".equals(suburb)) {
            return "Surry Hills";
        }
        return suburb.substring(0, 1).toUpperCase(Locale.ROOT) + suburb.substring(1);
    }

    private static Map<String, GeoPoint> landmarks() {
        Map<String, GeoPoint> map = new LinkedHashMap<>();
        put(map, "observatory hill", -33.8593, 151.2048);
        put(map, "brickfields", -33.8848, 151.1996);
        put(map, "white rabbit", -33.8866, 151.2003);
        put(map, "spice alley", -33.8849, 151.2017);
        put(map, "edition coffee", -33.8785, 151.2047);
        put(map, "opera house", -33.8568, 151.2153);
        put(map, "circular quay", -33.8612, 151.2108);
        return Map.copyOf(map);
    }

    private static Map<String, GeoPoint> suburbCentroids() {
        Map<String, GeoPoint> map = new LinkedHashMap<>();
        put(map, "alexandria", -33.9107, 151.1941);
        put(map, "bexley", -33.9500, 151.1280);
        put(map, "marrickville", -33.9103, 151.1553);
        put(map, "newtown", -33.8978, 151.1785);
        put(map, "chippendale", -33.8865, 151.2003);
        put(map, "surry hills", -33.8861, 151.2096);
        put(map, "haymarket", -33.8786, 151.2046);
        put(map, "the rocks", -33.8596, 151.2090);
        put(map, "bondi", -33.8915, 151.2767);
        put(map, "bronte", -33.9032, 151.2680);
        put(map, "glebe", -33.8798, 151.1850);
        put(map, "paddington", -33.8840, 151.2270);
        put(map, "darlinghurst", -33.8790, 151.2190);
        put(map, "redfern", -33.8928, 151.2040);
        put(map, "waterloo", -33.9004, 151.2077);
        put(map, "zetland", -33.9073, 151.2106);
        put(map, "enmore", -33.8997, 151.1716);
        put(map, "randwick", -33.9143, 151.2417);
        put(map, "coogee", -33.9209, 151.2555);
        put(map, "kensington", -33.9095, 151.2224);
        put(map, "kingsford", -33.9240, 151.2270);
        put(map, "maroubra", -33.9504, 151.2574);
        put(map, "clovelly", -33.9122, 151.2670);
        return Map.copyOf(map);
    }

    private static void put(Map<String, GeoPoint> map, String name, double lat, double lng) {
        map.put(name, new GeoPoint(lat, lng, "table"));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeocodingResponse(List<GeocodingHit> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeocodingHit(String name, double latitude, double longitude) {
    }
}
