package com.example.starter.marpol.domain;

/**
 * 排放类型：油类、垃圾、生活污水。
 */
public enum DischargeType {
    /** 油类排放，特殊区域内禁止。 */
    OIL,
    /** 垃圾排放，距最近陆地不得少于 12 海里。 */
    GARBAGE,
    /** 生活污水排放，特殊区域内禁止。 */
    SEWAGE
}
