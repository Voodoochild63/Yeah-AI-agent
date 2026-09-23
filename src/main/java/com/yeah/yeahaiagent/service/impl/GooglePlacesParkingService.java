package com.yeah.yeahaiagent.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.service.ParkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Places API (New) 停车检索。未配置 Key 或调用失败时降级到 {@link MockParkingService}。
 */
@Service
@Slf4j
public class GooglePlacesParkingService implements ParkingService {

    private static final String SEARCH_NEARBY_PATH = "/v1/places:searchNearby";
    private static final String SEARCH_TEXT_PATH = "/v1/places:searchText";
    private static final String FIELD_MASK =
            "places.displayName,places.formattedAddress,places.location,places.rating";
    private static final double SEARCH_RADIUS_METERS = 800d;

    private final RestClient restClient;
    private final MockParkingService mockParkingService;
    private final String apiKey;

    public GooglePlacesParkingService(
            RestClient.Builder restClientBuilder,
            MockParkingService mockParkingService,
            @Value("${parking.google.api-key:}") String apiKey) {
        this.restClient = restClientBuilder.baseUrl("https://places.googleapis.com").build();
        this.mockParkingService = mockParkingService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public ParkingInfo findNearbyParking(double lat, double lng, String poiName, String lang) {
        LanguageEnum language = LanguageEnum.fromCode(lang);
        if (apiKey.isBlank()) {
            log.info("未配置 parking.google.api-key，降级使用本地停车场数据");
            return mockParkingService.findNearbyParking(lat, lng, poiName, lang);
        }
        try {
            PlacesResponse nearby = searchNearby(lat, lng);
            ParkingInfo fromNearby = toParkingInfo(nearby, lat, lng, language);
            if (fromNearby.hasParkingRecommendation()) {
                return fromNearby;
            }
            PlacesResponse text = searchText(lat, lng, poiName);
            ParkingInfo fromText = toParkingInfo(text, lat, lng, language);
            if (fromText.hasParkingRecommendation()) {
                return fromText;
            }
            log.warn("Google Places 未返回停车场，降级使用本地数据。poiName={}", poiName);
            return mockParkingService.findNearbyParking(lat, lng, poiName, lang);
        } catch (Exception e) {
            log.warn("Google Places 停车检索失败，降级使用本地数据", e);
            return mockParkingService.findNearbyParking(lat, lng, poiName, lang);
        }
    }

    private PlacesResponse searchNearby(double lat, double lng) {
        if (!MockParkingService.isValidCoord(lat, lng)) {
            return new PlacesResponse(List.of());
        }
        Map<String, Object> center = new HashMap<>();
        center.put("latitude", lat);
        center.put("longitude", lng);

        Map<String, Object> circle = new HashMap<>();
        circle.put("center", center);
        circle.put("radius", SEARCH_RADIUS_METERS);

        Map<String, Object> locationRestriction = new HashMap<>();
        locationRestriction.put("circle", circle);

        Map<String, Object> body = new HashMap<>();
        body.put("includedTypes", List.of("parking"));
        body.put("maxResultCount", 5);
        body.put("rankPreference", "DISTANCE");
        body.put("locationRestriction", locationRestriction);
        return post(SEARCH_NEARBY_PATH, body);
    }

    private PlacesResponse searchText(double lat, double lng, String poiName) {
        String query = (poiName == null || poiName.isBlank())
                ? "parking garage Sydney"
                : "parking near " + poiName + " Sydney";
        Map<String, Object> body = new HashMap<>();
        body.put("textQuery", query);
        body.put("includedType", "parking");
        body.put("maxResultCount", 5);
        if (MockParkingService.isValidCoord(lat, lng)) {
            Map<String, Object> center = new HashMap<>();
            center.put("latitude", lat);
            center.put("longitude", lng);
            Map<String, Object> circle = new HashMap<>();
            circle.put("center", center);
            circle.put("radius", SEARCH_RADIUS_METERS);
            Map<String, Object> locationBias = new HashMap<>();
            locationBias.put("circle", circle);
            body.put("locationBias", locationBias);
        }
        return post(SEARCH_TEXT_PATH, body);
    }

    private PlacesResponse post(String path, Map<String, Object> body) {
        PlacesResponse response = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .body(body)
                .retrieve()
                .body(PlacesResponse.class);
        return response == null ? new PlacesResponse(List.of()) : response;
    }

    private ParkingInfo toParkingInfo(PlacesResponse response, double originLat, double originLng,
                                      LanguageEnum language) {
        if (response == null || response.places() == null || response.places().isEmpty()) {
            return ParkingInfo.none(null);
        }
        Place first = response.places().get(0);
        if (first == null) {
            return ParkingInfo.none(null);
        }
        double destLat = first.location() == null ? originLat : first.location().latitude();
        double destLng = first.location() == null ? originLng : first.location().longitude();
        String walking = MockParkingService.isValidCoord(originLat, originLng)
                && MockParkingService.isValidCoord(destLat, destLng)
                ? MockParkingService.formatWalking(
                MockParkingService.haversineMeters(originLat, originLng, destLat, destLng), language)
                : language.text("步行距离请以导航为准", "Use navigation for the walking distance");
        String name = first.displayName() == null ? null : first.displayName().text();
        return new ParkingInfo(
                name != null && !name.isBlank(),
                name,
                first.formattedAddress(),
                first.rating(),
                walking,
                language.text(
                        "建议提前确认限高、计费与 Early Bird 时段，节假日车位紧张时可改停邻近车库。",
                        "Check height limits, tariffs, and Early Bird hours in advance. Use a nearby garage if this one is full on holidays."),
                "parking near " + (name == null ? "Sydney" : name)
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlacesResponse(List<Place> places) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Place(
            DisplayName displayName,
            String formattedAddress,
            Double rating,
            LatLng location
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DisplayName(String text, String languageCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LatLng(double latitude, double longitude) {
    }
}
