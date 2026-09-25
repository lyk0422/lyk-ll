package com.example.starter.marpol.domain;

/**
 * 核查官对单条排放记录的结论。
 */
public enum Conclusion {
    /** 确认（合规或无异议）。 */
    CONFIRMED,
    /** 标注违规，立即生成缺陷项并限期整改。 */
    VIOLATION
}
