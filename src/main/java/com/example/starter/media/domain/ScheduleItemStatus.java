package com.example.starter.media.domain;

/**
 * 排期时段状态。
 * BOUND：已绑定当时有效的授权版本；BLOCKED：授权被撤销且该时段尚未开始；PLAYED：已完成播出，证据冻结。
 */
public enum ScheduleItemStatus {
    BOUND,
    BLOCKED,
    PLAYED
}
