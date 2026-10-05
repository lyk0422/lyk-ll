package com.example.starter.domain;

/**
 * 加钞计划类型。
 * REGULAR 常规计划：走逐级审批，押运时限 4 小时；
 * TEMPORARY 临时计划：节假日峰值预测触发，走快速审批通道（免逐级审批，生成即批准），
 * 押运时限压缩为 2 小时，不占用常规计划审批队列。
 */
public enum PlanType {
    REGULAR,
    TEMPORARY
}
