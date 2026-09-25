package com.example.starter.epidemic.domain;

/**
 * 特殊订正状态。
 */
public enum SpecialStatus {
    /** 确认人数不足两名，尚未生效。 */
    PENDING,
    /** 两名不同审核员确认完成，已生成新版本生效。 */
    APPLIED
}
