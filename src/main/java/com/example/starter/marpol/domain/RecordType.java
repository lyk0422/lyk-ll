package com.example.starter.marpol.domain;

/**
 * 排放记录类型。
 */
public enum RecordType {
    /** 油类：特殊区域内禁止排放。 */
    OIL,
    /** 垃圾：距最近陆地不得少于 12 海里。 */
    GARBAGE,
    /** 生活污水：特殊区域内禁止排放。 */
    SEWAGE
}
