package com.example.starter.sensor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同步参数：每类传感器的配对容忍窗口、迟到容忍与最大插值间距。
 *
 * <p>时间单位均为纳秒。窗口为闭区间：|校正时戳 - 目标时刻| &lt;= windowNanos 即满足。
 */
@ConfigurationProperties(prefix = "sensor.sync")
public class SyncProperties {

    /** 各传感器窗口/插值配置，键为传感器类型小写名。 */
    private Map<String, SensorWindow> windows = new LinkedHashMap<>();

    public Map<String, SensorWindow> getWindows() {
        return windows;
    }

    public void setWindows(Map<String, SensorWindow> windows) {
        this.windows = windows;
    }

    /**
     * 单个传感器的时间参数。
     */
    public static class SensorWindow {
        /** 配对容忍窗口半宽（纳秒，闭区间）。 */
        private long windowNanos;
        /** 迟到容忍（纳秒）：校正事件时戳早于水位线超过该值的消息记为 LATE。 */
        private long latenessNanos;
        /** RADAR/IMU 允许插值的相邻消息最大间距（纳秒，按校正时戳）。 */
        private long maxInterpolationGapNanos;

        public long getWindowNanos() {
            return windowNanos;
        }

        public void setWindowNanos(long windowNanos) {
            this.windowNanos = windowNanos;
        }

        public long getLatenessNanos() {
            return latenessNanos;
        }

        public void setLatenessNanos(long latenessNanos) {
            this.latenessNanos = latenessNanos;
        }

        public long getMaxInterpolationGapNanos() {
            return maxInterpolationGapNanos;
        }

        public void setMaxInterpolationGapNanos(long maxInterpolationGapNanos) {
            this.maxInterpolationGapNanos = maxInterpolationGapNanos;
        }
    }
}
