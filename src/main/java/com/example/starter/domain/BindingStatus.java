package com.example.starter.domain;

/**
 * 通关单子批绑定状态。
 */
public enum BindingStatus {
    /** 已预占：绑定成功、尚未签发核销 */
    RESERVED,
    /** 已核销：签发归档时由预占原子转入 */
    CONSUMED,
    /** 已释放：子批签发前撤回或通关单撤销，预占只释放一次 */
    RELEASED
}
