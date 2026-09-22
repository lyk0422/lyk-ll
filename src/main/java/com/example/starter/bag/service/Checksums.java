package com.example.starter.bag.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 校验值计算工具：负载 SHA-256 与段内消息链式 SHA-256。
 *
 * <p>链式规则：{@code h(i) = sha256(h(i-1) || topic || seq || timestampNs || typeId || payloadChecksum)}，
 * 其中 {@code h(0)} 为空串。段级校验值即段内最后一条消息的链值，因此任意长度的完整前缀都可独立验证，
 * 写入中断产生的未链入尾消息在校验时会被识别。
 */
public final class Checksums {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Checksums() {
    }

    /** 计算负载的 SHA-256，null 按空负载处理。 */
    public static String sha256(byte[] data) {
        return toHex(digest().digest(data == null ? new byte[0] : data));
    }

    /**
     * 在已有链值上追加一条消息，返回新链值。
     *
     * @param previous 上一条消息的链值，首条消息传空串
     */
    public static String chain(String previous, String topic, long seq, long timestampNs,
                               String typeId, String payloadChecksum) {
        MessageDigest md = digest();
        md.update(previous.getBytes(StandardCharsets.UTF_8));
        md.update(topic.getBytes(StandardCharsets.UTF_8));
        md.update(Long.toString(seq).getBytes(StandardCharsets.UTF_8));
        md.update(Long.toString(timestampNs).getBytes(StandardCharsets.UTF_8));
        md.update(typeId.getBytes(StandardCharsets.UTF_8));
        md.update(payloadChecksum.getBytes(StandardCharsets.UTF_8));
        return toHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
