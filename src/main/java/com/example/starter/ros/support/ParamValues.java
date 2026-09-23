package com.example.starter.ros.support;

import com.example.starter.ros.ParamType;

/**
 * 参数标量值与文本表示之间的转换。
 *
 * <p>数据库统一存储文本；读取时按 Schema 类型还原为强类型标量。
 */
public final class ParamValues {

    private ParamValues() {
    }

    /**
     * 将外部传入值（JSON 反序列化后的 String/Number/Boolean 或字符串字面量）转换为目标类型。
     *
     * @throws IllegalArgumentException 类型不匹配或无法解析
     */
    public static Object coerce(ParamType type, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (type) {
            case STRING -> raw instanceof String s ? s : String.valueOf(raw);
            case INT -> toLong(raw);
            case DOUBLE -> toDouble(raw);
            case BOOL -> toBoolean(raw);
        };
    }

    /** 将数据库文本还原为强类型标量。 */
    public static Object parse(ParamType type, String text) {
        if (text == null) {
            return null;
        }
        return switch (type) {
            case STRING -> text;
            case INT -> Long.parseLong(text.trim());
            case DOUBLE -> Double.parseDouble(text.trim());
            case BOOL -> Boolean.parseBoolean(text.trim());
        };
    }

    /** 强类型标量的规范化文本表示，用于落库与幂等哈希。 */
    public static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double d) {
            return Double.toString(d);
        }
        if (value instanceof Float f) {
            return Double.toString(f.doubleValue());
        }
        return String.valueOf(value);
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Long l) {
            return l;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException("value '" + raw + "' is not an integer");
            }
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("value '" + raw + "' is not an integer");
            }
        }
        throw new IllegalArgumentException("value '" + raw + "' is not an integer");
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("value '" + raw + "' is not a number");
            }
        }
        throw new IllegalArgumentException("value '" + raw + "' is not a number");
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(t)) {
                return Boolean.FALSE;
            }
        }
        throw new IllegalArgumentException("value '" + raw + "' is not a boolean");
    }
}
