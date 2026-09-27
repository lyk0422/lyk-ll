package com.example.starter.domain;

/**
 * 包装子批与通关单的绑定状态。
 */
public enum BindingStatus {
    /** 已预占：绑定成功，预占支数尚未核销 */
    RESERVED,
    /** 已核销：进口签发时本子批预占原子转为已核销 */
    WRITTEN_OFF,
    /** 已释放：签发前撤回子批或通关单撤销，预占只释放一次 */
    RELEASED
}
