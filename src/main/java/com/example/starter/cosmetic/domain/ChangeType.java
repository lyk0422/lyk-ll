package com.example.starter.cosmetic.domain;

/**
 * 备案变更类型。
 */
public enum ChangeType {

    /** 首次备案。 */
    INITIAL,
    /** 实质变更：涉及配方或功效宣称，重新走形式审查与公示并生成新备案编号。 */
    SUBSTANTIVE,
    /** 简易变更：仅包装文字类调整，不重新公示，生效记录引用原备案编号。 */
    SIMPLE
}
