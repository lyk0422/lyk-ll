package com.example.starter.domain;

/**
 * 包装子批通关单数量预占状态。
 */
public enum ReservationStatus {
    /** 预占中：已绑定通关单并冻结申报支数，尚未签发 */
    RESERVED,
    /** 已释放：签发前撤回子批或通关单被撤销，预占数量仅释放一次 */
    RELEASED,
    /** 已核销：进口子批签发原子动作内将预占数量转为已核销 */
    WRITTEN_OFF
}
