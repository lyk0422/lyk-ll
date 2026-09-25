package com.example.starter.wb.enums;

/**
 * 舱单版本状态。
 */
public enum ManifestStatus {
    /** 预配 */
    PRELIMINARY,
    /** 值机 */
    CHECKIN,
    /** 装载确认 */
    LOADED,
    /** 双岗复核通过（舱单冻结） */
    REVIEWED,
    /** 放行关闭（终态） */
    CLOSED,
    /** 已被新版本替代 */
    SUPERSEDED
}
