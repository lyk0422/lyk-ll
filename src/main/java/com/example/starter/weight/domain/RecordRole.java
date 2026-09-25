package com.example.starter.weight.domain;

/**
 * 过磅记录在证据链中的角色。
 */
public enum RecordRole {
    /**
     * 当前作为判定依据的有效记录。
     */
    AUTHORITATIVE,
    /**
     * 已被同“车牌+检测站+过磅时刻”的静态复检取代，仅保留可查。
     */
    SUPERSEDED,
    /**
     * 案件结案后才到达的迟到过磅数据，仅入证据备查，不改判已结案件。
     */
    LATE_EVIDENCE
}
