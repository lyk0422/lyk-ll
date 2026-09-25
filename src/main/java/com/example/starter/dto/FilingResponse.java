package com.example.starter.dto;

import java.time.Instant;

import com.example.starter.domain.Filing;

/**
 * 起爆备案响应。
 */
public record FilingResponse(
        long id,
        String requestId,
        long permitId,
        Instant filedAt) {

    /**
     * 由领域对象构造响应。
     */
    public static FilingResponse from(Filing f) {
        return new FilingResponse(f.id(), f.requestId(), f.permitId(), f.filedAt());
    }
}
