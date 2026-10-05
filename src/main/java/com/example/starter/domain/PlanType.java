package com.example.starter.domain;

/**
 * 加钞计划类型。
 * REGULAR 常规计划：走逐级审批，押运时限 4 小时；
 * TEMPORARY 节假日临时计划：快速通道免逐级审批（生成即放行），押运时限压缩为 2 小时。
 */
public enum PlanType {
    REGULAR,
    TEMPORARY
}
