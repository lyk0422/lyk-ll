package com.example.starter.rosbridge.ros;

/**
 * ROS 2 QoS 策略的最小描述，参与共享上游订阅的去重键。
 *
 * @param reliability 可靠性策略：RELIABLE / BEST_EFFORT
 * @param durability  持久性策略：VOLATILE / TRANSIENT_LOCAL
 * @param depth       历史深度，正整数
 */
public record Qos(String reliability, String durability, int depth) {

    public static final String RELIABLE = "RELIABLE";
    public static final String BEST_EFFORT = "BEST_EFFORT";
    public static final String VOLATILE = "VOLATILE";
    public static final String TRANSIENT_LOCAL = "TRANSIENT_LOCAL";

    public Qos {
        reliability = reliability == null || reliability.isBlank() ? RELIABLE : reliability.trim().toUpperCase();
        durability = durability == null || durability.isBlank() ? VOLATILE : durability.trim().toUpperCase();
        if (depth <= 0) {
            depth = 10;
        }
    }

    public static Qos defaults() {
        return new Qos(RELIABLE, VOLATILE, 10);
    }

    /** 生成规范化的 QoS 去重键。 */
    public String key() {
        return reliability + "|" + durability + "|" + depth;
    }
}
