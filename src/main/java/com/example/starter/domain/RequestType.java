package com.example.starter.domain;

/**
 * 幂等请求类型，参与 requestId 指纹计算。
 */
public enum RequestType {

    /** 批次申报。 */
    DECLARE,
    /** 田间检验提交。 */
    FIELD,
    /** 室内检测提交。 */
    LAB,
    /** 复检申请受理。 */
    RECHECK
}
