package com.example.starter.domain;

/**
 * 幂等请求类型。
 */
public enum RequestKind {
    /** 批次申报 */
    DECLARE,
    /** 田间检验提交 */
    FIELD,
    /** 室内检测提交 */
    LAB
}
