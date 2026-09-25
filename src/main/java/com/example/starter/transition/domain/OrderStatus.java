package com.example.starter.transition.domain;

/**
 * 改品种单流转状态。
 */
public enum OrderStatus {
    /** 已申请（方式判定完成，尚未执行）。 */
    APPLIED,
    /** 执行中（纸机/清洗资源已占用）。 */
    EXECUTING,
    /** 过渡料已处置（过渡卷登记/检验完成）。 */
    DISPOSED,
    /** 已完成（纸机占用已释放）。 */
    COMPLETED,
    /** 已归档（终态；迟到检验仅登记事件）。 */
    ARCHIVED
}
