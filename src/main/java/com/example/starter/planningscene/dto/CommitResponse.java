package com.example.starter.planningscene.dto;

/**
 * 提交成功响应。
 *
 * @param committed 是否产生新版本（同键同参重试时返回历史结果，仍为 true）
 * @param version 提交后的新版本号
 * @param replayed true 表示本次为幂等重放，响应体取自首次提交
 */
public record CommitResponse(boolean committed, long version, boolean replayed) {
}
