package com.example.starter.weight.domain;

/**
 * 复检申请与结论状态。
 */
public enum ReviewStatus {
    /**
     * 复检申请已受理、待结论；期间处罚不停止执行。
     */
    UNDER_REVIEW,
    /**
     * 复检维持原处罚。
     */
    UPHELD,
    /**
     * 复检推翻原处罚：原决定已撤销、记分与罚款已退还。
     */
    OVERTURNED
}
