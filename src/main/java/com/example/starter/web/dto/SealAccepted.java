package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 日结封存结果。alreadySealed=true 表示重复封存的幂等成功。
 */
public record SealAccepted(long commandId, String status, LocalDateTime sealedAt,
                           boolean alreadySealed) {
}
