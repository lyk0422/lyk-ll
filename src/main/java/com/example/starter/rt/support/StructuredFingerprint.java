package com.example.starter.rt.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.rt.domain.OperationType;

/**
 * 结构化幂等指纹：对操作类型、requestId 与全部业务参数做<b>带长度前缀与类型标签</b>的规范化编码后
 * 计算 SHA-256。
 *
 * <p>与简单的 {@code key=value\n} 拼接不同，本编码保证：</p>
 * <ul>
 *   <li>合法文本值即便包含换行、等号或任意“分隔符”，也无法伪造字段边界（长度前缀定界）；</li>
 *   <li>字符串 "1" 与整数 1、null 与字面量 "null" 类型不同，指纹不同；</li>
 *   <li>集合保留顺序并显式写入元素个数，[a,b] 与 [a,b] 重排、嵌套/重复元素均可区分；</li>
 *   <li>时刻按 UTC 绝对瞬间（epoch 秒+纳秒）编码，不同偏移表示同一瞬间指纹相同，不同瞬间必不同。</li>
 * </ul>
 */
public final class StructuredFingerprint {

    private StructuredFingerprint() {
    }

    /** 计算结构化指纹，返回 64 位十六进制 SHA-256。 */
    public static String of(OperationType operation, String requestId, Map<String, ?> params) {
        Buffer buffer = new Buffer();
        buffer.writeString(operation.name());
        buffer.writeString(requestId);
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        buffer.writeInt(sorted.size());
        sorted.forEach((key, value) -> {
            buffer.writeString(key);
            buffer.writeValue(value);
        });
        return sha256(buffer.toByteArray());
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 规范化编码缓冲：全部长度均以 4 字节大端前缀书写，杜绝分隔符注入。 */
    private static final class Buffer {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        void writeString(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            writeInt(bytes.length);
            out.writeBytes(bytes);
        }

        void writeInt(int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        void writeLong(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((value >>> shift) & 0xFF));
            }
        }

        /** 类型标签 + 值体；支持嵌套 Map/List、常用标量与 UTC 时刻。 */
        void writeValue(Object value) {
            if (value == null) {
                out.write('N');
            } else if (value instanceof String s) {
                out.write('S');
                writeString(s);
            } else if (value instanceof Boolean b) {
                out.write('B');
                out.write(b ? 1 : 0);
            } else if (value instanceof Integer i) {
                out.write('i');
                writeInt(i);
            } else if (value instanceof Long l) {
                out.write('l');
                writeLong(l);
            } else if (value instanceof Number n) {
                // Double/BigDecimal 等统一走十进制字符串标签，保持与输入字面量一致的精度表达。
                out.write('d');
                writeString(n.toString());
            } else if (value instanceof OffsetDateTime odt) {
                out.write('t');
                OffsetDateTime utc = odt.withOffsetSameInstant(java.time.ZoneOffset.UTC);
                writeLong(utc.toEpochSecond());
                writeLong(utc.toLocalTime().toNanoOfDay() % 1_000_000_000L);
            } else if (value instanceof Enum<?> e) {
                out.write('E');
                writeString(e.name());
            } else if (value instanceof Map<?, ?> map) {
                out.write('M');
                TreeMap<String, Object> nested = new TreeMap<>();
                map.forEach((k, v) -> nested.put(String.valueOf(k), v));
                writeInt(nested.size());
                nested.forEach((k, v) -> {
                    writeString(k);
                    writeValue(v);
                });
            } else if (value instanceof List<?> list) {
                out.write('L');
                writeInt(list.size());
                for (Object element : list) {
                    writeValue(element);
                }
            } else {
                throw new IllegalArgumentException("结构化指纹不支持的参数类型：" + value.getClass());
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
