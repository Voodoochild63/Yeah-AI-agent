package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yeah.yeahaiagent.config.SerperProperties;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    public SerperPlacesService(SerperProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
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
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("serper.api-key 未配置，跳过物理地点检索");
            return List.of();
        }
        try {
            PlacesResponse response = restClient.post()
                    .uri("/places")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-API-KEY", apiKey)
                    .body(Map.of("q", query.trim(), "gl", "au", "hl", "en"))
                    .retrieve()
                    .body(PlacesResponse.class);
            if (response == null || response.places() == null) {
                return List.of();
            }
            return response.places().stream()
                    .filter(place -> place != null && place.title() != null && !place.title().isBlank())
                    .filter(place -> place.rating() != null && place.rating() >= MIN_RATING)
                    .filter(place -> resolveRatingCount(place) >= MIN_RATING_COUNT)
                    .sorted(Comparator.comparing(SerperPlace::rating, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(MAX_RESULTS)
                    .map(place -> new RealPlaceDto(
                            place.title().trim(),
                            place.address(),
                            place.rating(),
                            resolveRatingCount(place),
                            firstNonBlank(place.category(), place.type())))
                    .toList();
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
            String type
    ) {
    }
}
