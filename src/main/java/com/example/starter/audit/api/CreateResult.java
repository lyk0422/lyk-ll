package com.example.starter.audit.api;

/**
 * 创建任务结果。
 *
 * @param task     任务详情（新建或幂等重放的原任务）
 * @param replayed 是否为同键同参数幂等重放（true 时 HTTP 返回 200，否则 201）
 */
public record CreateResult(TaskView task, boolean replayed) {
}
