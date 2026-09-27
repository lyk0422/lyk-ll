package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 结构化、无歧义的规范编码：TLV（类型标记 + UTF-8 字节长度前缀 + 内容）。
 * 即使合法文本本身含 ":" 或任何分隔符也不会产生歧义——长度前缀决定边界，
 * 且文本/整数/时刻/布尔使用不同类型标记，"1"、1、true 不会互相混淆。
 * 集合以元素计数前缀包裹，嵌套结构同样无歧义。
 */
public final class Canonical {

    private Canonical() {
    }

    /** 文本 token：t&lt;字节数&gt;:&lt;UTF-8 文本&gt;。 */
    public static String str(String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        return "t" + bytes.length + ":" + (value == null ? "" : value);
    }

    /** 整数 token：i&lt;十进制长度&gt;:&lt;十进制值&gt;。 */
    public static String integer(long value) {
        String text = Long.toString(value);
        return "i" + text.length() + ":" + text;
    }

    /** 布尔 token：b&lt;长度&gt;:true|false。 */
    public static String bool(boolean value) {
        String text = Boolean.toString(value);
        return "b" + text.length() + ":" + text;
    }

    /**
     * 精确时刻 token：以 epoch 毫秒编码（与库内 TIMESTAMP 精度一致），
     * 不依赖时区与格式字符串。
     */
    public static String instant(java.time.Instant value) {
        String text = Long.toString(value.toEpochMilli());
        return "m" + text.length() + ":" + text;
    }

    /** UTC 日期 token：固定 ISO yyyy-MM-dd。 */
    public static String date(java.time.LocalDate value) {
        String text = value.toString();
        return "d" + text.length() + ":" + text;
    }

    /**
     * 有序字段序列（对象）：o&lt;字段数&gt;: 后接“键 token + 值 token”交替。
     * 字段顺序由调用方显式给出；值本身为任意规范 token 或嵌套对象/列表。
     */
    public static String obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("字段必须以键值对出现");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("o").append(keyValues.length / 2).append(":");
        for (int i = 0; i < keyValues.length; i += 2) {
            sb.append(str((String) keyValues[i]));
            sb.append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    /**
     * 列表 token：l&lt;元素数&gt;: 后接各元素规范串。调用方须先对集合做确定性排序，
     * 使同一集合无论输入顺序如何都得到同一指纹。
     */
    public static String list(List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("l").append(items.size()).append(":");
        items.forEach(sb::append);
        return sb.toString();
    }

    /** 对规范串取 SHA-256，作为持久化指纹。 */
    public static String sha256(String canonical) {
        return Fingerprints.sha256(canonical);
    }
}
