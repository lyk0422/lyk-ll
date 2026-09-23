package com.example.starter.ros.model;

/**
 * ROS 参数支持的标量类型，风格上对齐 ROS 2 Parameter 的基础参数类型。
 */
public enum ParamType {
    STRING,
    INTEGER,
    DOUBLE,
    BOOLEAN;

    /**
     * 按 Schema 类型解析外部传入的 JSON 值，非法类型抛出 {@link IllegalArgumentException}。
     */
    public Object parse(Object raw) {
        if (raw == null) {
            return null;
        }
        switch (this) {
            case STRING -> {
                if (raw instanceof String s) {
                    return s;
                }
                throw new IllegalArgumentException("期望字符串类型，实际为 " + raw.getClass().getSimpleName());
            }
            case INTEGER -> {
                if (raw instanceof Integer i) {
                    return i.longValue();
                }
                if (raw instanceof Long l) {
                    return l;
                }
                if (raw instanceof Number n) {
                    long v = n.longValue();
                    if (n.doubleValue() != (double) v) {
                        throw new IllegalArgumentException("期望整数类型，实际为小数 " + raw);
                    }
                    return v;
                }
                throw new IllegalArgumentException("期望整数类型，实际为 " + raw.getClass().getSimpleName());
            }
            case DOUBLE -> {
                if (raw instanceof Number n) {
                    return n.doubleValue();
                }
                throw new IllegalArgumentException("期望浮点类型，实际为 " + raw.getClass().getSimpleName());
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                throw new IllegalArgumentException("期望布尔类型，实际为 " + raw.getClass().getSimpleName());
            }
            default -> throw new IllegalStateException("未知类型: " + this);
        }
    }

    /**
     * 将数据库中以字符串保存的值还原为强类型值。
     */
    public Object fromStored(String stored) {
        if (stored == null) {
            return null;
        }
        return switch (this) {
            case STRING -> stored;
            case INTEGER -> Long.parseLong(stored);
            case DOUBLE -> Double.parseDouble(stored);
            case BOOLEAN -> Boolean.parseBoolean(stored);
        };
    }
}
