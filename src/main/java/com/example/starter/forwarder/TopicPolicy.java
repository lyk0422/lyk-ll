package com.example.starter.forwarder;

/**
 * 单个 Topic 的离线缓存策略。
 *
 * @param type 策略类型
 * @param bound BOUNDED 策略的容量上限，其余策略忽略该值
 */
public record TopicPolicy(PolicyType type, int bound) {

    /**
     * 解析配置文本，如 "DROP"、"LATEST"、"FIFO"、"BOUNDED(10)"。
     */
    public static TopicPolicy parse(String text) {
        String normalized = text == null ? "" : text.trim().toUpperCase();
        if (normalized.startsWith("BOUNDED")) {
            int open = normalized.indexOf('(');
            int close = normalized.indexOf(')', open + 1);
            if (open < 0 || close < 0) {
                throw new IllegalArgumentException("BOUNDED 策略需要形如 BOUNDED(n) 的参数: " + text);
            }
            int bound = Integer.parseInt(normalized.substring(open + 1, close).trim());
            if (bound < 1) {
                throw new IllegalArgumentException("BOUNDED 容量必须 >= 1: " + text);
            }
            return new TopicPolicy(PolicyType.BOUNDED, bound);
        }
        return new TopicPolicy(PolicyType.valueOf(normalized), 0);
    }
}
