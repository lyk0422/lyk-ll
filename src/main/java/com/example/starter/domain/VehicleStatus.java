package com.example.starter.domain;

/**
 * 分车车单状态：创建即全量预占，未发车可整体撤销，发车出厂为终态，均不可事后改单。
 */
public enum VehicleStatus {
    /** 已全量预占、尚未发车出厂；可整体撤销释放或逐车核销发车。 */
    RESERVED,
    /** 已逐车核销发车出厂，终态，车单冻结清单与证据不可改。 */
    DEPARTED,
    /** 未发车即整体撤销，预占头只已释放，终态。 */
    REVOKED
}
