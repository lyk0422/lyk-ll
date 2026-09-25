package com.example.starter.schedule.model;

/**
 * 按日编排版本状态。
 */
public enum VersionStatus {

    /** 草稿：可接受编排、抢占与撤销。 */
    DRAFT,

    /** 已发布：冻结，抢占/撤销一律拒绝，紧急插入须开新版本。 */
    PUBLISHED
}
