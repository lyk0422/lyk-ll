package com.example.starter.forwarder.policy;

/**
 * Topic 离线保留策略。
 *
 * <ul>
 *   <li>DROP：离线时直接丢弃；</li>
 *   <li>LATEST：只保留最新一条；</li>
 *   <li>FIFO：完整顺序保留；</li>
 *   <li>BOUNDED(n)：最多保留 n 条，超出挤出最旧待发记录。</li>
 * </ul>
 */
public record RetentionPolicy(Kind kind, int bound) {

    public enum Kind {
        DROP, LATEST, FIFO, BOUNDED
    }

    public static RetentionPolicy drop() {
        return new RetentionPolicy(Kind.DROP, 0);
    }

    public static RetentionPolicy latest() {
        return new RetentionPolicy(Kind.LATEST, 1);
    }

    public static RetentionPolicy fifo() {
        return new RetentionPolicy(Kind.FIFO, Integer.MAX_VALUE);
    }

    public static RetentionPolicy bounded(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("BOUNDED 容量必须为正整数: " + n);
        }
        return new RetentionPolicy(Kind.BOUNDED, n);
    }

    /**
     * 解析配置文本，例如 {@code DROP}、{@code LATEST}、{@code FIFO}、{@code BOUNDED(5)}。
     */
    public static RetentionPolicy parse(String text) {
        String value = text.trim().toUpperCase();
        if (value.equals("DROP")) {
            return drop();
        }
        if (value.equals("LATEST")) {
            return latest();
        }
        if (value.equals("FIFO")) {
            return fifo();
        }
        if (value.startsWith("BOUNDED(") && value.endsWith(")")) {
            try {
                int n = Integer.parseInt(value.substring(8, value.length() - 1).trim());
                return bounded(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法 BOUNDED 策略: " + text, e);
            }
        }
        throw new IllegalArgumentException("无法识别的保留策略: " + text);
    }
}
