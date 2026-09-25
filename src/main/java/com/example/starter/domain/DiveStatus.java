package com.example.starter.domain;

/**
 * 潜次状态：作业申请→减压方案→潜水执行→出水观察→结案。
 */
public enum DiveStatus {
    /** 作业申请已受理，尚未出减压方案 */
    APPLIED,
    /** 减压方案已生成（可存在多个版本，当前版本未冻结） */
    PLANNED,
    /** 潜水执行中（已有成对生效的水下记录） */
    DIVING,
    /** 已出水，进入 2 小时观察期 */
    SURFACED,
    /** 观察期满无症状，结案终态 */
    CLOSED,
    /** 观察期出现症状，启动加压舱预案并冻结结案 */
    FROZEN
}
