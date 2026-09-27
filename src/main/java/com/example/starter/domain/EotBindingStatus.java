package com.example.starter.domain;

/**
 * 列尾绑定状态：ACTIVE 绑定中；RELEASED 已解除（提前解绑或销记，历史保留）。
 */
public enum EotBindingStatus {
    ACTIVE,
    RELEASED
}
