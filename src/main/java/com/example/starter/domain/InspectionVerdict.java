package com.example.starter.domain;

/**
 * 单次检验/检测结论：按批次申报等级门槛判定。
 */
public enum InspectionVerdict {
    /** 合格：达到申报等级门槛 */
    PASS,
    /** 不合格：低于申报等级门槛，可在规定期限内申请复检一次 */
    FAIL
}
