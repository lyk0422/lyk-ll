package com.example.starter.domain;

/**
 * 分车出厂车单状态：创建即预占冻结，仅 RESERVED 可整体撤销或逐车核销。
 */
public enum TruckManifestStatus {
    /** 已预占未发车：仅此状态可整体撤销或发车核销，清单冻结不可改。 */
    RESERVED,
    /** 已发车核销：单车全部头只转已出厂，终态不可改。 */
    RELEASED,
    /** 已整体撤销：释放全部头只预占，终态不可改，不能事后改单。 */
    REVOKED
}
