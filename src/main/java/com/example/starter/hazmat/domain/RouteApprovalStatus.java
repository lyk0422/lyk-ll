package com.example.starter.hazmat.domain;

/**
 * 运单路线核准记录状态。
 */
public enum RouteApprovalStatus {

    /** 待核准（申请时/紧急改线时写入）。 */
    PENDING,
    /** 已核准（当前生效路线，同一运单至多一条）。 */
    APPROVED,
    /** 核准驳回（类别不匹配、禁行、管制时段冲突等）。 */
    REJECTED,
    /** 已被新核准路线替代而终止（改线生效时原路线写入）。 */
    TERMINATED
}
