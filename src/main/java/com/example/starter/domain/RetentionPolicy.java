package com.example.starter.domain;

/**
 * Topic 离线保留策略配置。
 *
 * @param type     策略类型，不为空
 * @param capacity 仅 {@link PolicyType#BOUNDED} 使用，表示每 Topic 最多保留条数；其余策略为 null
 */
public record RetentionPolicy(PolicyType type, Integer capacity) {

    public RetentionPolicy {
        if (type == null) {
            throw new IllegalArgumentException("policy type must not be null");
        }
        if (type == PolicyType.BOUNDED) {
            if (capacity == null || capacity <= 0) {
                throw new IllegalArgumentException("BOUNDED capacity must be positive");
            }
        } else {
            capacity = null;
        }
    }

    public static RetentionPolicy of(PolicyType type) {
        return new RetentionPolicy(type, null);
    }

    public static RetentionPolicy bounded(int capacity) {
        return new RetentionPolicy(PolicyType.BOUNDED, capacity);
    }

    /**
     * 解析策略文本，如 {@code DROP}、{@code LATEST}、{@code FIFO}、{@code BOUNDED(100)}。
     */
    public static RetentionPolicy parse(String text) {
        String value = text == null ? "" : text.trim().toUpperCase();
        if (value.startsWith(PolicyType.BOUNDED.name())) {
            int open = value.indexOf('(');
            int close = value.lastIndexOf(')');
            if (open < 0 || close < open) {
                throw new IllegalArgumentException("invalid bounded policy: " + text);
            }
            int n = Integer.parseInt(value.substring(open + 1, close).trim());
            return bounded(n);
        }
        return of(PolicyType.valueOf(value));
    }

    /** 持久化与接口展示用的规范文本。 */
    public String format() {
        return type == PolicyType.BOUNDED ? type.name() + "(" + capacity + ")" : type.name();
    }
}
