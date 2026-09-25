package com.example.starter.idempotent;

/**
 * 幂等执行结果包装。
 *
 * @param result 业务结果
 * @param replay 是否为同键同参重放
 * @param httpStatus 首次受理时记录的 HTTP 状态码
 */
public record ReplayResult<T>(T result, boolean replay, int httpStatus) {
}
