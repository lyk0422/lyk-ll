package com.example.starter.scene.repo;

/**
 * 已落地幂等请求行。
 *
 * @param requestId 幂等请求 ID
 * @param requestHash 规范化请求体哈希
 * @param sceneVersion 提交后的场景版本
 * @param responseJson 首次成功响应 JSON
 */
public record AppliedRequestRow(String requestId, String requestHash, long sceneVersion, String responseJson) {
}
