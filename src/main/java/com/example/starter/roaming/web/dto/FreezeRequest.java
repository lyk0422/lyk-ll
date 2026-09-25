package com.example.starter.roaming.web.dto;

import java.time.LocalDate;

/**
 * 清分批冻结/计算请求：冻结指定 UTC 日内已结束且未归批的会话。
 *
 * @param requestId     幂等键
 * @param settlementDay 清分归属日（UTC）
 */
public record FreezeRequest(
        String requestId,
        LocalDate settlementDay) {
}
