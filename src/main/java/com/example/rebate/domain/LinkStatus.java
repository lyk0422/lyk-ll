package com.example.rebate.domain;

/**
 * 批次-单证关联状态。
 */
public enum LinkStatus {
    /** 已关联到有效批次，单证被占用。 */
    LINKED,
    /** 已释放（撤批作废或创建批次失败回滚后不可见）。 */
    RELEASED
}
