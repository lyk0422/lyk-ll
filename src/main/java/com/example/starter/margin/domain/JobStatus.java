package com.example.starter.margin.domain;

/**
 * 强平作业状态。指令生成后：PENDING 可被达标入账撤销；EXECUTED 为终态不可撤销。
 */
public enum JobStatus {
    /** 待发强平（低于 110% 或追保逾期后生成，尚未开始执行） */
    PENDING,
    /** 待发期间入账使比例达标，作业撤销 */
    CANCELLED,
    /** 已执行（终态，不可撤销） */
    EXECUTED
}
