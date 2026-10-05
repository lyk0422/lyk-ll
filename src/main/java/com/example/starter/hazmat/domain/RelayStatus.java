package com.example.starter.hazmat.domain;

/**
 * 押运接力段状态。
 */
public enum RelayStatus {

    /** 进行中：已设置安全接力点，等待双方押运员打卡换班。 */
    OPEN,
    /** 换班完成：交班与接班双方均在禁行起点前打卡。 */
    COMPLETED,
    /** 违约：未能在禁行起点前完成换班，运单冻结并转处置。 */
    BREACHED
}
