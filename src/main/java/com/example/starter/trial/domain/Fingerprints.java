package com.example.starter.trial.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 入组幂等指纹：覆盖 requestId、筛选号、中心与知情同意签署时刻（UTC），并绑定试验。
 * 同键同参指纹一致；任一参数变化指纹即不同，用于 409 冲突判定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String trialCode, String requestId, String screeningNo,
                            String centerCode, java.time.Instant consentTimeUtc) {
        String raw = String.join("|",
                trialCode, requestId, screeningNo, centerCode,
                consentTimeUtc.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
