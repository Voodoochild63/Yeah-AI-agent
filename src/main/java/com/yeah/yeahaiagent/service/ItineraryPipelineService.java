package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeah.yeahaiagent.model.dto.CustomPoiDto;
import com.yeah.yeahaiagent.model.dto.RagContext;
import com.yeah.yeahaiagent.model.dto.RealPlaceDto;
import com.yeah.yeahaiagent.model.dto.TasteProfile;
import com.yeah.yeahaiagent.model.enums.SpotCategory;
import com.yeah.yeahaiagent.resilience.UpstreamApiGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Retrieve-Read-Filter-Reason：先生成英文检索词，再抓真实高分店，最后交给主模型排线。
 */
@Service
@Slf4j
public class ItineraryPipelineService {

    private final ChatClient chatClient;
    private final SerperPlacesService serperPlacesService;
    private final ObjectMapper objectMapper;
    private final UpstreamApiGuard upstreamApiGuard;

    public ItineraryPipelineService(ChatModel dashscopeChatModel,
                                    SerperPlacesService serperPlacesService,
                                    ObjectMapper objectMapper) {
        this(dashscopeChatModel, serperPlacesService, objectMapper, null);
    }

    @Autowired
    public ItineraryPipelineService(ChatModel dashscopeChatModel,
                                    SerperPlacesService serperPlacesService,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<UpstreamApiGuard> upstreamApiGuard) {
        this.chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultTemplateRenderer((template, variables) -> template)
                .build();
        this.serperPlacesService = serperPlacesService;
        this.objectMapper = objectMapper;
        this.upstreamApiGuard = upstreamApiGuard == null ? null : upstreamApiGuard.getIfAvailable();
    }

    public record PipelineContext(List<String> searchQueries, List<RealPlaceDto> verifiedPlaces) {
        public boolean hasVerifiedPlaces() {
            return verifiedPlaces != null && !verifiedPlaces.isEmpty();
        }
    }

    public PipelineContext retrieve(String query, RagContext ragContext) {
        List<String> searchQueries = generateSearchQueries(query, ragContext);
        List<RealPlaceDto> verified = serperPlacesService.searchAndMerge(searchQueries);
        log.info("agentic retrieve queries={}, verified={}", searchQueries.size(), verified.size());
        return new PipelineContext(searchQueries, verified);
    }

    public String formatVerifiedPool(List<RealPlaceDto> places) {
        if (places == null || places.isEmpty()) {
            return "(empty — no verified Google Maps venues passed the 4.0 / 20-review filter)";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (RealPlaceDto place : places) {
            builder.append(index++)
                    .append(". ")
                    .append(nullToEmpty(place.getTitle()))
                    .append(" | rating ")
                    .append(place.getRating() == null ? "?" : place.getRating())
                    .append(" (")
                    .append(place.getRatingCount() == null ? 0 : place.getRatingCount())
                    .append(" reviews) | ")
                    .append(nullToEmpty(place.getCategory()))
                    .append(" | ")
                    .append(nullToEmpty(place.getAddress()));
            if (place.getLatitude() != null && place.getLongitude() != null) {
                builder.append(" | lat ").append(place.getLatitude())
                        .append(" lng ").append(place.getLongitude());
            }
            if (place.getOpeningHours() != null && !place.getOpeningHours().isEmpty()) {
                builder.append(" | hours ").append(String.join("; ", place.getOpeningHours()));
            } else if (place.getOpeningHoursText() != null && !place.getOpeningHoursText().isBlank()) {
                builder.append(" | hours ").append(place.getOpeningHoursText());
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    public String formatTasteAndPrivateRag(RagContext ragContext) {
        TasteProfile taste = ragContext == null ? null : ragContext.getTasteProfile();
        List<CustomPoiDto> pois = ragContext == null || ragContext.getCustomPois() == null
                ? List.of()
                : ragContext.getCustomPois();
        StringBuilder builder = new StringBuilder();
        builder.append("- aesthetic: ").append(blankToDash(taste == null ? null : taste.getAesthetic())).append('\n');
        builder.append("- photoStyle: ").append(blankToDash(taste == null ? null : taste.getPhotoStyle())).append('\n');
        builder.append("- pace: ").append(describePace(taste == null ? null : taste.getPace())).append('\n');
        builder.append("- customNotes: ").append(blankToDash(taste == null ? null : taste.getCustomNotes())).append('\n');
        builder.append("- customPois:\n");
        if (pois.isEmpty()) {
            builder.append("  (none)");
            return builder.toString();
        }
        for (CustomPoiDto poi : pois) {
            if (poi == null || poi.getPoiName() == null || poi.getPoiName().isBlank()) {
                continue;
            }
            builder.append("  - ").append(poi.getPoiName().trim());
            if (poi.getCategory() != null && !poi.getCategory().isBlank()) {
                builder.append(" / ").append(poi.getCategory().trim());
            }
            if (poi.getPersonalTip() != null && !poi.getPersonalTip().isBlank()) {
                builder.append(" — ").append(poi.getPersonalTip().trim());
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private List<String> generateSearchQueries(String query, RagContext ragContext) {
        TasteProfile taste = ragContext == null ? null : ragContext.getTasteProfile();
        String userBlock = """
                User request:
                __QUERY__

                Taste profile:
                aesthetic=__AESTHETIC__
                photoStyle=__PHOTO__
                pace=__PACE__
                customNotes=__NOTES__

                Return ONLY a JSON object: {"queries":["specialty coffee Surry Hills","art gallery Chippendale"]}
                Generate 1-2 English Google Maps search phrases for high-quality venues in Sydney.
                No markdown, no explanation.
                """
                .replace("__QUERY__", nullToEmpty(query))
                .replace("__AESTHETIC__", blankToDash(taste == null ? null : taste.getAesthetic()))
                .replace("__PHOTO__", blankToDash(taste == null ? null : taste.getPhotoStyle()))
                .replace("__PACE__", describePace(taste == null ? null : taste.getPace()))
                .replace("__NOTES__", blankToDash(taste == null ? null : taste.getCustomNotes()));
        try {
            if (upstreamApiGuard != null) {
                upstreamApiGuard.acquireLlm();
            }
            String raw = chatClient.prompt()
                    .templateRenderer((template, variables) -> template)
                    .system("""
                            You write short English Google Maps search queries for Sydney venues.
                            Output JSON only.
                            If the user wants a walk, stroll, sea breeze, park or exhibition, queries MUST be parks, coastal walks, lookouts or galleries — never restaurants.
                            If the request is a full-day / 一日游, include a sunset lookout query and a dinner query.
                            """)
                    .user(userBlock)
                    .call()
                    .content();
            List<String> parsed = parseQueries(raw);
            if (!parsed.isEmpty()) {
                return parsed.stream().limit(ItineraryDayScope.isFullDay(query) ? 4 : 2).toList();
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("search-query LLM call failed, using fallback", ex);
        }
        return fallbackQueries(query, taste);
    }

    private List<String> parseQueries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = extractJson(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                return objectMapper.convertValue(node, new TypeReference<List<String>>() {
                });
            }
            if (node.has("queries") && node.get("queries").isArray()) {
                return objectMapper.convertValue(node.get("queries"), new TypeReference<List<String>>() {
                });
            }
        } catch (Exception ex) {
            log.warn("could not parse search queries: {}", raw);
        }
        return List.of();
    }

    private static String extractJson(String raw) {
        String trimmed = raw.trim();
        int object = trimmed.indexOf('{');
        int array = trimmed.indexOf('[');
        if (object >= 0 && (array < 0 || object < array)) {
            return trimmed.substring(object);
        }
        if (array >= 0) {
            return trimmed.substring(array);
        }
        return trimmed;
    }

    private static List<String> fallbackQueries(String query, TasteProfile taste) {
        List<String> queries = new ArrayList<>();
        String aesthetic = taste == null ? "" : nullToEmpty(taste.getAesthetic()).toLowerCase(Locale.ROOT);
        if (query != null && query.toLowerCase(Locale.ROOT).contains("coffee")
                || query != null && query.contains("咖啡")
                || aesthetic.contains("咖啡")) {
            queries.add("specialty coffee Sydney");
        }
        if (query != null && (query.toLowerCase(Locale.ROOT).contains("gallery")
                || query.contains("展") || query.contains("美术"))) {
            queries.add("art gallery Chippendale Sydney");
        }
        if (query != null && SpotCategory.looksLikeWalk(query.toLowerCase(Locale.ROOT))) {
            queries.add("scenic coastal walk Sydney harbour");
            queries.add("Barangaroo Reserve night walk");
        }
        if (ItineraryDayScope.isFullDay(query)) {
            queries.add("sunset lookout Sydney harbour");
            queries.add("Japanese restaurant Sydney dinner");
        }
        if (queries.isEmpty()) {
            queries.add("specialty coffee Sydney");
            queries.add("independent cafe Sydney");
        }
        return queries.stream().limit(ItineraryDayScope.isFullDay(query) ? 4 : 2).toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToDash(String value) {
        String trimmed = nullToEmpty(value);
        return trimmed.isEmpty() ? "-" : trimmed;
    }

    private static String describePace(String pace) {
        if (pace == null || pace.isBlank()) {
            return "-";
        }
        return switch (pace.trim().toLowerCase(Locale.ROOT)) {
            case "relaxed" -> "relaxed — 松弛，少走多停";
            case "moderate" -> "moderate — 适中，节奏平衡";
            case "active" -> "active — 充实打卡，多点串联、停留偏短";
            default -> pace.trim();
        };
    }
}
