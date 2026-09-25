package com.example.starter.pv.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.pv.Seriousness;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 初报提交请求。若同一患者+药品+事件已存在活动个例，则按随访并入原个例；
 * 若仅存在已关闭个例，则新建关联个例并引用原编号。
 *
 * @param submittedAt 提交时刻（UTC），为空时取服务器时钟；仅供演示与测试注入
 */
public record InitialReportRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String patientId,
        @NotBlank @Size(max = 128) String drugName,
        @NotBlank @Size(max = 256) String adverseEvent,
        @NotNull Seriousness seriousness,
        @NotNull LocalDate awarenessDate,
        @NotBlank @Size(max = 2048) String narrative,
        @Size(max = 512) String lateReason,
        Instant submittedAt) {
}
