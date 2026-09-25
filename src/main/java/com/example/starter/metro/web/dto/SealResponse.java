package com.example.starter.metro.web.dto;

import java.time.LocalDate;

/**
 * 日结封账响应。alreadySealed=true 表示重复封账的幂等成功。
 */
public record SealResponse(
        LocalDate batchDate,
        String status,
        long totalFen,
        boolean alreadySealed) {
}
