package com.example.starter.rt.dto;

/**
 * 幂等操作的首次响应快照：HTTP 状态码 + 可直接 JSON 序列化的响应体。
 * 业务拒绝（422/409 等首次产生的拒绝）同样记录，同键同参重放时原样返回。
 *
 * @param httpStatus 首次响应 HTTP 状态码
 * @param body       首次响应体
 */
public record RecordedResponse(int httpStatus, Object body) {
}
