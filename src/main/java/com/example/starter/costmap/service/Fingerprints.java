package com.example.starter.costmap.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 障碍物请求关键参数的确定性 SHA-256 指纹，用于同 requestId 异参重放的 409 判定。
 *
 * <p>eventTime 为 null（取服务端时刻）时以空串占位，因此两次均省略 eventTime
 * 的重放视为同参；一旦显式给出不同时间戳即视为异参。</p>
 */
final class Fingerprints {

    private Fingerprints() {
    }

    static String of(String sourceId, String obstacleId, int priority, String kind,
                     int x0, int y0, int x1, int y1, Long eventTime, long ttlMillis) {
        String canonical = String.join("|",
                sourceId, obstacleId, Integer.toString(priority), kind,
                Integer.toString(x0), Integer.toString(y0),
                Integer.toString(x1), Integer.toString(y1),
                eventTime == null ? "" : Long.toString(eventTime),
                Long.toString(ttlMillis));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
