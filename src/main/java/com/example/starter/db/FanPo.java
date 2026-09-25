package com.example.starter.db;

import java.time.LocalDateTime;

/**
 * 风机持久化对象。
 */
public record FanPo(
        long id,
        String code,
        String name,
        LocalDateTime createdAt
) {
}
