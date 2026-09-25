package com.example.starter.roaming.web.dto;

import java.time.OffsetDateTime;

/**
 * 会话结束请求。
 *
 * @param requestId 幂等键
 * @param sessionNo 会话号
 * @param endTime   结束时刻（UTC）；为空时取服务端当前时刻
 */
public record StopRequest(
        String requestId,
        String sessionNo,
        OffsetDateTime endTime) {
}
