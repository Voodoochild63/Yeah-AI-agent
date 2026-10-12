package com.yeah.yeahaiagent.util;

/**
 * 球面距离。行程后处理用它判断相邻景点是否超出步行范围。
 */
public final class GeoDistanceUtils {

    private static final int EARTH_RADIUS_KM = 6371;

    private GeoDistanceUtils() {
    }

    public static double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    public static double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        return calculateDistanceKm(lat1, lon1, lat2, lon2) * 1000d;
    }

    public static boolean isValidCoord(double lat, double lon) {
        return lat >= -90d && lat <= 90d
                && lon >= -180d && lon <= 180d
                && !(lat == 0d && lon == 0d);
    }
}
