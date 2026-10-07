package com.example.starter.runway;

/**
 * 施工段状态：LOCKED（闭锁中）→ COMPLETED（两个不同验收人凭据齐全后完成）。
 * 段完成仅消除本单该段闭锁；重叠区域仍被其他闭锁段占用时不可用。
 */
public enum ConstructionSegmentStatus {
    LOCKED,
    COMPLETED
}
