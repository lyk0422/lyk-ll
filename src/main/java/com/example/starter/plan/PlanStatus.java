package com.example.starter.plan;

/**
 * 放疗计划状态机：设计→物理审核→医师批准→首次验证→分次执行→疗程结案；
 * INVALIDATED 表示被新版本替代即刻失效。
 */
public enum PlanStatus {
    /** 设计中，可提交物理审核 */
    DESIGN,
    /** 物理审核通过，可提交医师批准 */
    PHYSICS_REVIEWED,
    /** 医师已批准，可提交首次模体验证 */
    APPROVED,
    /** 首次验证通过，可开始分次执行 */
    VERIFIED,
    /** 分次执行中 */
    EXECUTING,
    /** 疗程结案，终态 */
    CLOSED,
    /** 已失效（换版后旧版本） */
    INVALIDATED
}
