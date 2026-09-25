package com.example.starter.cosmetic.domain;

/**
 * 备案类型：首次备案、实质变更（配方/功效宣称，重新审查并生成新编号）、简易变更（包装文字类，引用原编号）。
 */
public enum ChangeType {
    /** 首次备案。 */
    INITIAL,
    /** 实质变更：涉及配方或功效宣称，重新形式审查并生成新备案编号。 */
    SUBSTANTIAL,
    /** 简易变更：包装文字类，引用原备案编号。 */
    SIMPLE
}
