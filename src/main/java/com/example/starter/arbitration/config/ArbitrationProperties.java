package com.example.starter.arbitration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仲裁服务可调参数。所有时间单位均为毫秒（UTC epoch millis），位置单位为弧度。
 */
@ConfigurationProperties(prefix = "arbitration")
public class ArbitrationProperties {

    private final Lease lease = new Lease();
    private final Command command = new Command();
    private final Controller controller = new Controller();
    private final Reaper reaper = new Reaper();
    private final Safety safety = new Safety();

    public Lease getLease() {
        return lease;
    }

    public Command getCommand() {
        return command;
    }

    public Controller getController() {
        return controller;
    }

    public Reaper getReaper() {
        return reaper;
    }

    public Safety getSafety() {
        return safety;
    }

    /** 租约参数。 */
    public static class Lease {
        /** 租约默认有效期（毫秒）。 */
        private long ttlMs = 5000;

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }
    }

    /** 指令校验参数。 */
    public static class Command {
        /** 未来指令时戳容忍窗口（毫秒）。 */
        private long futureToleranceMs = 500;
        /** 指令最大年龄（毫秒）：now - timestampMs 超过该值视为过期。 */
        private long maxAgeMs = 2000;
        /** 待执行指令在队列中的最大停留时间（毫秒）。 */
        private long queueTimeoutMs = 10000;

        public long getFutureToleranceMs() {
            return futureToleranceMs;
        }

        public void setFutureToleranceMs(long futureToleranceMs) {
            this.futureToleranceMs = futureToleranceMs;
        }

        public long getMaxAgeMs() {
            return maxAgeMs;
        }

        public void setMaxAgeMs(long maxAgeMs) {
            this.maxAgeMs = maxAgeMs;
        }

        public long getQueueTimeoutMs() {
            return queueTimeoutMs;
        }

        public void setQueueTimeoutMs(long queueTimeoutMs) {
            this.queueTimeoutMs = queueTimeoutMs;
        }
    }

    /** 控制器心跳参数。 */
    public static class Controller {
        /** 超过该时长未上报心跳即判定控制器失联（毫秒）。 */
        private long heartbeatTimeoutMs = 8000;

        public long getHeartbeatTimeoutMs() {
            return heartbeatTimeoutMs;
        }

        public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) {
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        }
    }

    /** 后台扫描参数。 */
    public static class Reaper {
        /** 扫描间隔（毫秒）。 */
        private long intervalMs = 200;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    /** 安全降级默认参数，可被单资源配置覆盖。 */
    public static class Safety {
        /** 默认降级动作。 */
        private String defaultAction = "HOLD";
        /** PRESET_POSITION 使用的预设位置（弧度）。 */
        private double presetPosition = 0.0;

        public String getDefaultAction() {
            return defaultAction;
        }

        public void setDefaultAction(String defaultAction) {
            this.defaultAction = defaultAction;
        }

        public double getPresetPosition() {
            return presetPosition;
        }

        public void setPresetPosition(double presetPosition) {
            this.presetPosition = presetPosition;
        }
    }
}
