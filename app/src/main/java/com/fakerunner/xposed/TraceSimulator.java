package com.fakerunner.xposed;

import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹模拟器：根据配置的轨迹点和速度，计算当前位置、速度、方向。
 *
 * 核心逻辑：
 * 1. 把轨迹点连成折线，计算每段长度。
 * 2. 根据已跑时间和速度，计算当前在折线上的位置。
 * 3. 在相邻轨迹点之间做线性插值。
 * 4. 由相邻位置计算 bearing（方向）和 speed（速度）。
 */
public class TraceSimulator {

    // 内部表示一个带距离的轨迹点
    private static class TrackPoint {
        double lat;
        double lng;
        double cumulativeDistance;  // 从起点到该点的累计距离（米）

        TrackPoint(double lat, double lng, double cumulativeDistance) {
            this.lat = lat;
            this.lng = lng;
            this.cumulativeDistance = cumulativeDistance;
        }
    }

    private final List<TrackPoint> trackPoints = new ArrayList<>();
    private final double totalDistance;    // 轨迹总长度（米）
    private final double speed;            // 速度（m/s）
    private long startTimeMs;              // 开始时间

    // 上一次的位置（用于计算方向）
    private double lastLat;
    private double lastLng;
    private boolean hasLast = false;

    public TraceSimulator() {
        this.speed = Config.RUN_SPEED;
        this.startTimeMs = System.currentTimeMillis();

        // 构建带累计距离的轨迹点
        double cumulative = 0.0;
        for (int i = 0; i < Config.TRACK_POINTS.length; i++) {
            double lat = Config.TRACK_POINTS[i][0];
            double lng = Config.TRACK_POINTS[i][1];
            if (i > 0) {
                double prevLat = Config.TRACK_POINTS[i - 1][0];
                double prevLng = Config.TRACK_POINTS[i - 1][1];
                cumulative += distance(prevLat, prevLng, lat, lng);
            }
            trackPoints.add(new TrackPoint(lat, lng, cumulative));
        }
        this.totalDistance = cumulative;
        this.lastLat = trackPoints.get(0).lat;
        this.lastLng = trackPoints.get(0).lng;
    }

    /**
     * 重置开始时间（重新开始跑步）。
     */
    public void reset() {
        startTimeMs = System.currentTimeMillis();
        hasLast = false;
    }

    /**
     * 获取当前位置的 Location 对象。
     * 包含完整的 lat/lng/altitude/accuracy/speed/bearing/time/elapsedRealtimeNanos。
     */
    public Location getCurrentLocation() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        double distanceMoved = speed * (elapsedMs / 1000.0);

        // 处理绕圈：如果超过总距离，取模
        if (totalDistance > 0) {
            distanceMoved = distanceMoved % totalDistance;
        }

        // 找到当前所在的线段
        double[] pos = interpolate(distanceMoved);
        double lat = pos[0];
        double lng = pos[1];

        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        loc.setAltitude(Config.ALTITUDE);
        loc.setAccuracy(Config.LOCATION_ACCURACY);

        // 速度必须和实际位移匹配
        loc.setSpeed((float) speed);

        // 方向：由上一次位置和当前位置计算
        float bearing = hasLast ? bearing(lastLat, lastLng, lat, lng) : 0f;
        loc.setBearing(bearing);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            loc.setBearingAccuracyDegrees(10.0f);
        }

        // 时间戳：必须单调递增
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        // 更新上一次位置
        lastLat = lat;
        lastLng = lng;
        hasLast = true;

        return loc;
    }

    /**
     * 根据已跑距离，在轨迹上插值得到当前坐标。
     */
    private double[] interpolate(double distance) {
        if (trackPoints.size() < 2) {
            return new double[]{trackPoints.get(0).lat, trackPoints.get(0).lng};
        }

        // 找到距离所在的线段 [i-1, i]
        for (int i = 1; i < trackPoints.size(); i++) {
            TrackPoint prev = trackPoints.get(i - 1);
            TrackPoint curr = trackPoints.get(i);
            if (distance <= curr.cumulativeDistance) {
                double segmentLength = curr.cumulativeDistance - prev.cumulativeDistance;
                double ratio = segmentLength > 0
                        ? (distance - prev.cumulativeDistance) / segmentLength
                        : 0;
                double lat = prev.lat + (curr.lat - prev.lat) * ratio;
                double lng = prev.lng + (curr.lng - prev.lng) * ratio;
                return new double[]{lat, lng};
            }
        }

        // 超出最后一个点，返回最后一个点
        TrackPoint last = trackPoints.get(trackPoints.size() - 1);
        return new double[]{last.lat, last.lng};
    }

    /**
     * 计算两点之间的距离（米），使用 Haversine 公式。
     */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 计算从 (lat1,lng1) 到 (lat2,lng2) 的方位角（度，0-360，正北为 0）。
     */
    public static float bearing(double lat1, double lng1, double lat2, double lng2) {
        double dLng = Math.toRadians(lng2 - lng1);
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double y = Math.sin(dLng) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad)
                - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLng);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brng + 360) % 360);
    }

    public double getTotalDistance() {
        return totalDistance;
    }
}
