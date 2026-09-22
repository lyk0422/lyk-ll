package com.example.starter.web.dto;

import com.example.starter.match.MatchResult;

/**
 * 路线请求响应。
 *
 * @param requestId    请求业务标识
 * @param mapVersionId 请求固定绑定的地图版本
 * @param status       SUCCESS 或 FAILED
 * @param errorCode    失败错误码；成功时为 null
 * @param result       匹配结果快照；失败时为 null
 */
public record RouteResponse(
        String requestId,
        String mapVersionId,
        String status,
        String errorCode,
        MatchResult result) {
}
