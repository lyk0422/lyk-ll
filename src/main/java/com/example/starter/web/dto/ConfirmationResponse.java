package com.example.starter.web.dto;

/**
 * 发电确认结果。
 *
 * @param requestId 确认请求幂等键
 * @param order     调度单最新视图
 * @param result    CONFIRMED 确认成功；TIMEOUT_ABNORMAL 恰满 30 分钟超时已升级异常；
 *                  LATE_RECORDED 迟到确认仅记录事件、不改变终态
 */
public record ConfirmationResponse(
        String requestId,
        OrderResponse order,
        String result
) {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String TIMEOUT_ABNORMAL = "TIMEOUT_ABNORMAL";
    public static final String LATE_RECORDED = "LATE_RECORDED";
}
