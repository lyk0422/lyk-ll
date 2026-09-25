package com.example.starter.pv.web;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 随访信息提交请求；日期按 UTC 日界解释。
 *
 * @param requestId 客户端幂等键
 * @param followUpDate 随访信息日（UTC），不得晚于当前 UTC 日期
 * @param info 随访信息内容
 * @param reporterCode 合成报告人编码
 * @param reporterName 合成报告人姓名
 */
public record FollowUpRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull LocalDate followUpDate,
        @NotBlank @Size(max = 2000) String info,
        @NotBlank @Size(max = 64) String reporterCode,
        @Size(max = 128) String reporterName) {
}
