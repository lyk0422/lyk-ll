package com.example.starter.sync;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 同步参数配置。windowMs 为各传感器允许与目标融合时刻的最大偏差(毫秒)；
 * interpolationWindowMs 为允许插值的跨度上限(毫秒)；latenessMs 为水位线之后的迟到容忍(毫秒)。
 */
@ConfigurationProperties(prefix = "sync")
public class SyncProperties {

    private long latenessMs = 500;

    private Map<SensorType, Long> windowMs = defaultWindows();

    private Map<SensorType, Long> interpolationWindowMs = defaultInterpolationWindows();

    private static Map<SensorType, Long> defaultWindows() {
        Map<SensorType, Long> m = new EnumMap<>(SensorType.class);
        m.put(SensorType.LIDAR, 50L);
        m.put(SensorType.RADAR, 60L);
        m.put(SensorType.CAMERA, 40L);
        m.put(SensorType.IMU, 20L);
        return m;
    }

    private static Map<SensorType, Long> defaultInterpolationWindows() {
        Map<SensorType, Long> m = new EnumMap<>(SensorType.class);
        m.put(SensorType.LIDAR, 100L);
        m.put(SensorType.RADAR, 120L);
        m.put(SensorType.CAMERA, 80L);
        m.put(SensorType.IMU, 40L);
        return m;
    }

    public long windowMs(SensorType type) {
        return windowMs.getOrDefault(type, 50L);
    }

    public long interpolationWindowMs(SensorType type) {
        return interpolationWindowMs.getOrDefault(type, 2 * windowMs(type));
    }

    public long getLatenessMs() {
        return latenessMs;
    }

    public void setLatenessMs(long latenessMs) {
        this.latenessMs = latenessMs;
    }

    public Map<SensorType, Long> getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(Map<SensorType, Long> windowMs) {
        this.windowMs = windowMs;
    }

    public Map<SensorType, Long> getInterpolationWindowMs() {
        return interpolationWindowMs;
    }

    public void setInterpolationWindowMs(Map<SensorType, Long> interpolationWindowMs) {
        this.interpolationWindowMs = interpolationWindowMs;
    }
}
