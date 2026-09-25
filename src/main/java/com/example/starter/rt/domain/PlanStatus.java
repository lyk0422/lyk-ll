package com.example.starter.rt.domain;

/**
 * 放疗计划版本生命周期状态。
 * 设计→物理审核→医师批准→首次模体验证→分次执行→疗程结案；换版后旧版本为失效。
 */
public enum PlanStatus {

    /** 设计中：尚未通过物理审核。 */
    DESIGN,
    /** 物理审核通过：约束逐项合格且总剂量勾稽一致。 */
    REVIEWED,
    /** 医师批准：占用患者“唯一在执行计划”守卫。 */
    APPROVED,
    /** 首次治疗前模体验证通过（偏差≤3%）。 */
    VERIFIED,
    /** 分次执行中：至少完成一个分次。 */
    EXECUTING,
    /** 疗程结案终态。 */
    CLOSED,
    /** 旧版本即刻失效：计划换版后由同一事务置为该状态。 */
    INVALID
}
