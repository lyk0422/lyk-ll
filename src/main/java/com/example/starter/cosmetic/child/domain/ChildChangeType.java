package com.example.starter.cosmetic.child.domain;

/**
 * 儿童备案变更类型。
 */
public enum ChildChangeType {

    /** 首次儿童备案。 */
    INITIAL,
    /** 材料补正：形式审查驳回后补齐分龄资料，沿用同号生成新修订。 */
    CORRECTION,
    /** 实质变更：儿童属性、年龄区间、适用年龄标识范围、配方或儿童功效变化，重新审查公示并生成新编号。 */
    SUBSTANTIVE,
    /** 简易变更：仅包装文字（含产品名称文字）调整，不重新公示。 */
    SIMPLE
}
