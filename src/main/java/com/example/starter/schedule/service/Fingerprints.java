package com.example.starter.schedule.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 编排申请指纹：覆盖 requestId、卫星、地面站、窗口与优先级。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String requestId, String satelliteCode, String stationCode,
                            long windowId, int priority) {
        String raw = String.join("|", requestId, satelliteCode, stationCode,
                Long.toString(windowId), Integer.toString(priority));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
