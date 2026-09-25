package com.example.starter.domain;

/**
 * 放行结论。
 */
public enum ReleaseStatus {

    /** 放行成功，已同事务生成发血记录。 */
    SUCCESS,
    /** 拒绝放行（单位状态/效期/时间窗不满足）。 */
    REJECTED
}
