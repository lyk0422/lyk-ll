package com.example.starter.domain;

/**
 * 检测申请（占用记录）状态。
 */
public enum ReservationStatus {
    /**
     * 已预留：aliquot 上有对应体积被占用但尚未消耗。
     */
    RESERVED,
    /**
     * 检测已开始：预留体积转为已消耗，不可取消。
     */
    CONSUMED,
    /**
     * 已取消：仅 RESERVED 可取消或被污染事件联动取消，占用体积已释放。
     */
    CANCELLED
}
