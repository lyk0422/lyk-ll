package com.example.starter.quarantine.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 疫点封锁发布请求。发布即刻生效；blockadeId 为幂等键。
 *
 * @param blockadeId 封锁发布业务键
 * @param farmId     被封锁养殖场编号
 * @param reason     封锁原因（合成）
 */
public record BlockadeRequest(
        @NotBlank @Size(max = 64) String blockadeId,
        @NotBlank @Size(max = 64) String farmId,
        @Size(max = 255) String reason) {
}
