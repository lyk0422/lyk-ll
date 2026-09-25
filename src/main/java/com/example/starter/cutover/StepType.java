package com.example.starter.cutover;

/**
 * 步骤类型：
 * NORMAL 普通割接步骤；CRITICAL 关键验证点（10 分钟未通过触发回退）；
 * HARDCHECK 硬性检查（任一失败立即触发回退）；ROLLBACK 回退步骤。
 */
public enum StepType {
    NORMAL,
    CRITICAL,
    HARDCHECK,
    ROLLBACK
}
