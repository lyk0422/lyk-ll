package com.example.starter.cosmetic.domain;

/**
 * 备案类别。
 */
public enum FilingCategory {

    /** 成人备案（默认，既有流程不变）。 */
    ADULT,
    /** 儿童备案：须通过分龄审查，年龄范围以整数月闭区间表示。 */
    CHILD
}
