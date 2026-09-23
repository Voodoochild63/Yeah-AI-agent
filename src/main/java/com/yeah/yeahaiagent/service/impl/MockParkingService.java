package com.yeah.yeahaiagent.service.impl;

import com.yeah.yeahaiagent.model.dto.ParkingInfo;
import com.yeah.yeahaiagent.model.enums.LanguageEnum;
import com.yeah.yeahaiagent.service.ParkingService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本地零成本停车适配器：内置悉尼核心打卡圈真实停车场，按店名/区域与经纬度打分。
 */
@Service
public class MockParkingService implements ParkingService {

    /**
     * 行人步速约 80 m/min
     */
    private static final double WALKING_METERS_PER_MINUTE = 80d;

    private static final List<ParkingLot> PARKING_LOTS = List.of(
            new ParkingLot(
                    "Wilson Parking - Market City",
                    "2 Quay St, Haymarket NSW 2000",
                    -33.8793, 151.2047, 4.1,
                    List.of("haymarket", "chinatown", "market city", "edition", "darling harbour"),
                    "支持 Early Bird 优惠，进场请注意限高 2.1m",
                    "Early Bird rates available. Watch the 2.1m height limit on entry."
            ),
            new ParkingLot(
                    "Secure Parking - Goulburn Street",
                    "20-26 Goulburn St, Sydney NSW 2000",
                    -33.8778, 151.2068, 3.8,
                    List.of("haymarket", "world square", "goulburn", "cbd"),
                    "24 小时开放，周末晚间相对好停，出口闸机高峰期会排队",
                    "Open 24 hours. Easier to find a bay on weekend evenings; expect a queue at the exit boom."
            ),
            new ParkingLot(
                    "Wilson Parking - Citipark Surry Hills",
                    "2-38 Baptist St, Surry Hills NSW 2010",
                    -33.8868, 151.2112, 4.0,
                    List.of("surry hills", "single o", "record store", "baptist", "crown"),
                    "距离 Crown St / Reservoir St 咖啡带步行很近，周末建议 10 点前到",
                    "Short walk to the Crown St / Reservoir St cafe strip. Arrive before 10am on weekends."
            ),
            new ParkingLot(
                    "Secure Parking - Riley Street",
                    "102-104 Riley St, Surry Hills NSW 2010",
                    -33.8824, 151.2136, 3.9,
                    List.of("surry hills", "darlinghurst", "oxford", "riley"),
                    "适合 Oxford St 一带，注意单行线进出，限高 2.0m",
                    "Handy for Oxford St. Note one-way access and a 2.0m height limit."
            ),
            new ParkingLot(
                    "Wilson Parking - The Clocktower",
                    "35 Harrington St, The Rocks NSW 2000",
                    -33.8596, 151.2083, 4.2,
                    List.of("the rocks", "observatory", "harrington", "sunset"),
                    "步行可达 Observatory Hill 日落机位，进场限高 2.0m",
                    "Walkable to the Observatory Hill sunset spot. Height limit 2.0m."
            ),
            new ParkingLot(
                    "Secure Parking - Circular Quay",
                    "1 Alfred St, Sydney NSW 2000",
                    -33.8616, 151.2109, 4.0,
                    List.of("circular quay", "opera", "quay", "opera house"),
                    "节假日极易满，满库可改停 The Rocks 后沿 Hickson Rd 步行",
                    "Fills quickly on holidays. If full, park in The Rocks and walk along Hickson Rd."
            ),
            new ParkingLot(
                    "Wilson Parking - Broadway Sydney",
                    "1 Bay St, Broadway NSW 2007",
                    -33.8841, 151.1947, 4.0,
                    List.of("chippendale", "broadway", "white rabbit", "central", "glebe"),
                    "白兔美术馆方向沿 Abercrombie St 步行，商场内可直达电梯",
                    "Walk along Abercrombie St toward White Rabbit Gallery. Lifts inside the mall."
            ),
            new ParkingLot(
                    "Newtown Council Car Park",
                    "1-5 Church St, Newtown NSW 2042",
                    -33.8975, 151.1795, 3.7,
                    List.of("newtown", "king street", "enmore", "church"),
                    "King St 路边 2P 非常紧张，优先进库；晚间注意限时收费",
                    "Street 2P on King St is scarce. Use the garage first, and watch evening time limits."
            ),
            new ParkingLot(
                    "Wilson Parking - ICC Darling Harbour",
                    "14 Darling Dr, Sydney NSW 2000",
                    -33.8752, 151.2005, 4.1,
                    List.of("darling harbour", "barangaroo", "pyrmont", "icc"),
                    "周末会展日建议提前预约，步行到滨水步道约 4 分钟",
                    "Book ahead on event weekends. About a 4-minute walk to the waterfront."
            )
    );

    /**
     * 私域 POI 近似坐标，行程节点只有店名时用来估算步行距离
     */
    private static final List<PoiCoord> POI_COORDS = List.of(
            new PoiCoord("Single O", -33.8847, 151.2118),
            new PoiCoord("The Record Store", -33.8839, 151.2125),
            new PoiCoord("White Rabbit", -33.8847, 151.1995),
            new PoiCoord("Edition Coffee", -33.8788, 151.2042),
            new PoiCoord("Observatory Hill", -33.8590, 151.2049),
            new PoiCoord("Circular Quay", -33.8612, 151.2108),
            new PoiCoord("Surry Hills", -33.8845, 151.2110),
            new PoiCoord("Haymarket", -33.8790, 151.2050),
            new PoiCoord("The Rocks", -33.8595, 151.2080),
            new PoiCoord("Newtown", -33.8978, 151.1785),
            new PoiCoord("Chippendale", -33.8860, 151.1990)
    );

    @Override
    public ParkingInfo findNearbyParking(double lat, double lng, String poiName, String lang) {
        LanguageEnum language = LanguageEnum.fromCode(lang);
        ResolvedOrigin origin = resolveOrigin(lat, lng, poiName);
        String query = compact(poiName);

        ParkingLot best = PARKING_LOTS.stream()
                .max(Comparator.comparingDouble(lot -> score(lot, origin, query)))
                .orElse(null);
        if (best == null) {
            return ParkingInfo.none(language.text("当前区域暂无可用停车场建议",
                    "No parking recommendation for this area."));
        }

        double meters = haversineMeters(origin.lat(), origin.lng(), best.lat(), best.lng());
        return new ParkingInfo(
                true,
                best.name(),
                best.address(),
                best.rating(),
                formatWalking(meters, language),
                language.text(best.tipZh(), best.tipEn()),
                "parking near " + (poiName == null ? "Sydney" : poiName.trim())
        );
    }

    private double score(ParkingLot lot, ResolvedOrigin origin, String query) {
        double meters = haversineMeters(origin.lat(), origin.lng(), lot.lat(), lot.lng());
        // 距离越近分越高；1km 以外迅速衰减
        double distanceScore = 1000d / (meters + 80d);
        double nameScore = 0;
        if (!query.isBlank()) {
            String lotName = compact(lot.name());
            if (query.contains(lotName) || lotName.contains(query)) {
                nameScore += 8;
            }
            for (String alias : lot.aliases()) {
                String compactAlias = compact(alias);
                if (query.contains(compactAlias) || compactAlias.contains(query)) {
                    nameScore += 10;
                }
            }
        }
        return distanceScore + nameScore;
    }

    private ResolvedOrigin resolveOrigin(double lat, double lng, String poiName) {
        if (isValidCoord(lat, lng)) {
            return new ResolvedOrigin(lat, lng);
        }
        String query = compact(poiName);
        if (!query.isBlank()) {
            for (PoiCoord poi : POI_COORDS) {
                String poiKey = compact(poi.name());
                if (query.contains(poiKey) || poiKey.contains(query)) {
                    return new ResolvedOrigin(poi.lat(), poi.lng());
                }
            }
        }
        // 默认落到悉尼市中心，避免无坐标时所有停车场同分
        return new ResolvedOrigin(-33.8688, 151.2093);
    }

    static boolean isValidCoord(double lat, double lng) {
        if (lat == 0d && lng == 0d) {
            return false;
        }
        return lat >= -90d && lat <= 90d && lng >= -180d && lng <= 180d;
    }

    static String formatWalking(double meters, LanguageEnum language) {
        int roundedMeters = (int) Math.round(meters);
        int minutes = Math.max(1, (int) Math.round(meters / WALKING_METERS_PER_MINUTE));
        return language.text(
                "步行约 %d 分钟 (%dm)".formatted(minutes, roundedMeters),
                "~%d mins walk (%dm)".formatted(minutes, roundedMeters));
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earthRadius * Math.asin(Math.sqrt(a));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String compact(String value) {
        return normalize(value).replace(" ", "");
    }

    private record ParkingLot(
            String name,
            String address,
            double lat,
            double lng,
            double rating,
            List<String> aliases,
            String tipZh,
            String tipEn
    ) {
    }

    private record PoiCoord(String name, double lat, double lng) {
    }

    private record ResolvedOrigin(double lat, double lng) {
    }
}
