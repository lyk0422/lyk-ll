package com.example.starter.scene.dto;

/**
 * 批量修改成功响应。
 *
 * @param requestId 回显的幂等请求 ID
 * @param version 本次提交产生的场景新版本号
 * @param replayed 是否为幂等重放（同键同参直接返回首次结果）
 */
public record ApplyResponse(String requestId, long version, boolean replayed) {
}
