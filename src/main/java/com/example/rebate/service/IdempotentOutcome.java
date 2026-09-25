package com.example.rebate.service;

/**
 * 幂等入口执行结果。
 *
 * @param first {@code true} 本次为首次执行；{@code false} 同键重放
 * @param status 最终 HTTP 状态码（首次执行业务码或首次存档码）
 * @param firstBody 首次执行的响应体对象；重放时为 {@code null}
 * @param replayBody 重放时返回的首次响应 JSON；首次执行为 {@code null}
 */
public record IdempotentOutcome(
        boolean first, int status, Object firstBody, String replayBody) {

    public static IdempotentOutcome first(int status, Object body) {
        return new IdempotentOutcome(true, status, body, null);
    }

    public static IdempotentOutcome replay(int status, String bodyJson) {
        return new IdempotentOutcome(false, status, null, bodyJson);
    }
}
