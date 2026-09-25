package com.example.starter.marpol.domain;

/**
 * 港口国核查状态。
 */
public enum InspectionState {
    /** 进行中：可逐条确认/标注，结论可改。 */
    OPEN,
    /** 已签发：终态，结论不可再改，只能走复查补充证据。 */
    ISSUED
}
