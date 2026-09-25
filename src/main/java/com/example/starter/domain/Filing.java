package com.example.starter.domain;

import java.time.Instant;

/**
 * 起爆备案记录。
 */
public record Filing(
        long id,
        String requestId,
        String fingerprint,
        long permitId,
        Instant filedAt) {
}
