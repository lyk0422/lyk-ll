package com.example.starter.rosconfig.model;

/**
 * ROS 参数支持的标量类型。
 */
public enum ParamType {
    /** 字符串 */
    STRING,
    /** 64 位整数 */
    INTEGER,
    /** 双精度浮点数 */
    DOUBLE,
    /** 布尔值 */
    BOOLEAN;

    /**
     * 判断规范化后的 Java 值是否属于该类型。
     */
    public boolean matches(Object value) {
        return switch (this) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Long;
            case DOUBLE -> value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    /**
     * 将 JSON 反序列化得到的对象转换为该类型的规范值，类型不兼容时抛出 {@link IllegalArgumentException}。
     * JSON 显式 null 表示清空/未设置，原样返回 null。
     */
    public Object coerce(Object raw) {
        if (raw == null) {
            return null;
        }
        switch (this) {
            case STRING:
                return raw instanceof String s ? s : String.valueOf(raw);
            case INTEGER:
                if (raw instanceof Number n) {
                    long v = n.longValue();
                    if (n.doubleValue() != (double) v) {
                        throw new IllegalArgumentException("not an integer: " + raw);
                    }
                    return v;
                }
                if (raw instanceof String s) {
                    try {
                        return Long.parseLong(s.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("not an integer: " + raw);
                    }
                }
                throw new IllegalArgumentException("not an integer: " + raw);
            case DOUBLE:
                if (raw instanceof Number n) {
                    return n.doubleValue();
                }
                if (raw instanceof String s) {
                    try {
                        return Double.parseDouble(s.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("not a double: " + raw);
                    }
                }
                throw new IllegalArgumentException("not a double: " + raw);
            case BOOLEAN:
                if (raw instanceof Boolean b) {
                    return b;
                }
                if (raw instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                    return Boolean.parseBoolean(s.trim());
                }
                throw new IllegalArgumentException("not a boolean: " + raw);
            default:
                throw new IllegalStateException("unknown type: " + this);
        }
    }
}
