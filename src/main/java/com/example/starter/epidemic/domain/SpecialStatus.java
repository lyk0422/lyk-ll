package com.example.starter.epidemic.domain;

/**
 * 归档后特殊订正的确认状态：须两名不同审核员先后确认。
 */
public enum SpecialStatus {
    /** 已发起、等待第二名（不同）审核员确认；单人确认时卡片数据不变更。 */
    WAITING_SECOND,
    /** 两名不同审核员均已确认，订正生效。 */
    EFFECTIVE
}
