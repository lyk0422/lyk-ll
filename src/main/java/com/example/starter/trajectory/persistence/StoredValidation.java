package com.example.starter.trajectory.persistence;

/**
 * 已持久化的校验记录行。
 *
 * @param requestId   幂等键
 * @param ruleVersion 规则版本
 * @param requestHash 请求参数哈希（不含 requestId），用于同键异参检测
 * @param resultJson  结果 JSON
 */
public record StoredValidation(String requestId, String ruleVersion, String requestHash, String resultJson) {
}
