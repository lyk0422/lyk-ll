package com.example.starter.domain;

/**
 * 包装子批绑定状态。
 */
public enum BindingStatus {
    /** 预占中：绑定成功，申报支数已计入通关单预占 */
    RESERVED,
    /** 已核销：签发归档原子由预占转入 */
    CONSUMED,
    /** 已释放：签发前撤回或通关单撤销，预占支数已退回 */
    RELEASED
}
