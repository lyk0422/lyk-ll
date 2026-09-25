package com.example.starter.payment.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：覆盖 requestId、渠道流水号、金额与商户号。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String requestId, String channelSerialNo, BigDecimal amount, String merchantNo) {
        String raw = String.join("|", requestId, channelSerialNo, amount.stripTrailingZeros().toPlainString(),
                merchantNo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
