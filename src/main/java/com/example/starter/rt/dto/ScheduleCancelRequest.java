package com.example.starter.rt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 排程取消请求。仅 BOOKED（已发布未完成）排程可取消；取消原子释放设备时长与分次占用。
 *
 * @param scheduleId      排程ID
 * @param expectedVersion 客户端所见排程版本（发布为1，取消/执行成功后为2），不一致返回409
 * @param requestId       取消幂等键；失败不占键
 */
public record ScheduleCancelRequest(
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long scheduleId,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer expectedVersion,
        @NotBlank(message = "不能为空") String requestId) {
}
