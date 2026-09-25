package com.example.starter.marpol.domain;

/**
 * 核查条目结论。
 */
public enum Decision {
    /** 确认。 */
    CONFIRMED,
    /** 标注违规：立即生成缺陷项并限期整改。 */
    VIOLATION
}
