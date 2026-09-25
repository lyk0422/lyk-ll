package com.example.starter.marpol.domain;

/**
 * 港口国核查任务状态。
 */
public enum InspectionStatus {
    /** 核查进行中，核查官可逐条确认或标注违规。 */
    OPEN,
    /** 已签发，终态：结论不可修改，重复签发幂等成功。 */
    ISSUED
}
