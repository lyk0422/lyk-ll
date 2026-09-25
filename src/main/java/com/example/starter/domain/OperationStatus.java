package com.example.starter.domain;

/**
 * 潜次状态：作业申请生成方案后为 PLANNED，潜水执行中为 EXECUTING，
 * 出水观察中为 OBSERVING，观察期出现症状启动加压舱预案后为 CHAMBER（冻结结案），
 * 正常结案为终态 CLOSED。
 */
public enum OperationStatus {
    /** 已受理申请并生成减压方案，尚未开始潜水。 */
    PLANNED,
    /** 潜水执行中（已产生水下记录）。 */
    EXECUTING,
    /** 出水后 2 小时观察期内。 */
    OBSERVING,
    /** 观察期出现症状，已启动加压舱预案，结案被永久冻结。 */
    CHAMBER,
    /** 终态：已结案。 */
    CLOSED
}
