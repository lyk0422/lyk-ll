package com.example.starter.cutover.domain;

/**
 * 步骤类型：VERIFY 为关键验证点，适用 10 分钟超时与硬性检查回退判据。
 */
public enum StepType {

    NORMAL,
    VERIFY
}
