package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 治疗性用药直报请求。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不改变状态。
 *
 * <p>直报须两名不同养殖端人员确认（confirmedBy 恰好两个不同标识），单人直报不生效；
 * 药品名称须存在于合成兽药目录，目录外拒绝受理且不改变原出栏日；
 * 同一批次同一给药时刻只允许一条进行中直报。</p>
 *
 * @param requestId         幂等键
 * @param batchNo           养殖批次号
 * @param drugName          兽药名称（须在合成兽药目录内）
 * @param administeredAtUtc 本次治疗给药时刻（UTC），休药期重算起算点之一
 * @param confirmedBy       两名养殖端确认人标识（恰好两人且互不相同）
 */
public record TreatmentReportRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotBlank(message = "兽药名称不能为空")
        @Size(max = 128)
        String drugName,

        @NotNull(message = "给药时刻不能为空")
        Instant administeredAtUtc,

        @NotNull(message = "确认人列表不能为空")
        List<@NotBlank(message = "确认人标识不能为空") @Size(max = 64) String> confirmedBy
) {
}
