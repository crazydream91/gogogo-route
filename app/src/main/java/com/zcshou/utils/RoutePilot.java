package com.zcshou.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线巡航计算：给定一串经纬度航点，按设定速度逐步推进。
 * 纯数学，无 Android 依赖，方便单测。
 */
public class RoutePilot {
    public static class Pt {
        public final double lat;
        public final double lng;

        public Pt(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
        }
    }

    private final List<Pt> mPoints = new ArrayList<>();
    private int mIndex = 0;
    private boolean mLoop = false;
    private boolean mFinished = true;
    private double mSpeed = 3.0;   // m/s

    public void setRoute(List<Pt> points, boolean loop) {
        mPoints.clear();
        if (points != null) {
            mPoints.addAll(points);
        }
        mLoop = loop;
        mIndex = mPoints.size() > 1 ? 1 : 0;
        mFinished = mPoints.size() < 2;
    }

    public void stop() {
        mFinished = true;
    }

    public boolean isRunning() {
        return !mFinished;
    }

    public boolean isLoop() {
        return mLoop;
    }

    public void setLoop(boolean loop) {
        mLoop = loop;
    }

    public double getSpeed() {
        return mSpeed;
    }

    public void setSpeed(double speed) {
        if (speed < 0.2) {
            speed = 0.2;
        }
        if (speed > 60.0) {
            speed = 60.0;
        }
        mSpeed = speed;
    }

    public int getIndex() {
        return mIndex;
    }

    public int size() {
        return mPoints.size();
    }

    public List<Pt> getPoints() {
        return mPoints;
    }

    /** 当前应该走向的航点；没有则 null */
    public Pt target() {
        if (mFinished || mPoints.isEmpty()) {
            return null;
        }
        return mPoints.get(Math.min(mIndex, mPoints.size() - 1));
    }

    /**
     * 前进一帧。
     *
     * @param curLat 当前纬度
     * @param curLng 当前经度
     * @param dtSec  本帧时长（秒）
     * @return {lat, lng, bearing}；巡航结束返回 null
     */
    public double[] step(double curLat, double curLng, double dtSec) {
        if (mFinished || mPoints.size() < 2) {
            return null;
        }
        Pt target = mPoints.get(Math.min(mIndex, mPoints.size() - 1));
        double stepLen = mSpeed * Math.max(0.01, dtSec);
        double dist = distance(curLat, curLng, target.lat, target.lng);

        double newLat;
        double newLng;
        double bearing;
        if (dist <= Math.max(stepLen, 0.4)) {
            newLat = target.lat;
            newLng = target.lng;
            bearing = dist < 0.05 ? 0 : bearing(curLat, curLng, target.lat, target.lng);
            mIndex++;
            if (mIndex >= mPoints.size()) {
                if (mLoop) {
                    mIndex = 0;
                } else {
                    mFinished = true;
                }
            }
        } else {
            bearing = bearing(curLat, curLng, target.lat, target.lng);
            double rad = Math.toRadians(bearing);
            newLat = curLat + (stepLen * Math.cos(rad)) / 110574.0;
            double cosLat = Math.cos(Math.toRadians(curLat));
            if (Math.abs(cosLat) < 1e-6) {
                cosLat = 1e-6;
            }
            newLng = curLng + (stepLen * Math.sin(rad)) / (111320.0 * cosLat);
        }
        return new double[] {newLat, newLng, bearing};
    }

    /** 两点间距离（米），Haversine */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 从点 1 指向点 2 的方位角（度，正北 0，顺时针） */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** "lat,lng;lat,lng" -> List<Pt> */
    public static List<Pt> parse(String text) {
        List<Pt> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] parts = text.trim().split(";");
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            String[] xy = p.trim().split(",");
            if (xy.length != 2) {
                continue;
            }
            try {
                out.add(new Pt(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
            } catch (NumberFormatException e) {
                // 跳过坏点
            }
        }
        return out;
    }

    /** List<Pt> -> "lat,lng;lat,lng" */
    public static String format(List<Pt> points) {
        StringBuilder sb = new StringBuilder();
        for (Pt p : points) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(p.lat).append(',').append(p.lng);
        }
        return sb.toString();
    }
}
