package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 治疗性用药直报请求。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不改变状态。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>须两名不同的养殖端人员确认，单人直报不生效；</li>
 *   <li>药品名称须在合成兽药目录内，目录外名称拒绝受理且不改变原出栏日；</li>
 *   <li>受理后触发批次休药期重算（最大休药天数与最晚给药时刻合并），原最早出栏日作废；</li>
 *   <li>同一批次同一给药时刻仅允许一条直报；批次已出栏核销时只记迟到事件并告警。</li>
 * </ul>
 *
 * @param requestId         幂等键
 * @param batchNo           养殖批次号
 * @param administeredAtUtc 最晚给药时刻（UTC）
 * @param drugNames         药品名称列表（合成兽药目录内名称）
 * @param confirmers        两名养殖端确认人标识，须为两人且互不相同
 */
public record TreatmentReportRequest(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 64)
        String requestId,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotNull(message = "给药时刻不能为空")
        Instant administeredAtUtc,

        @NotEmpty(message = "药品名称列表不能为空")
        List<@NotBlank(message = "药品名称不能为空") @Size(max = 128) String> drugNames,

        @NotNull(message = "确认人列表不能为空")
        @Size(min = 2, max = 2, message = "治疗直报须两名养殖端人员确认")
        List<@NotBlank(message = "确认人不能为空") @Size(max = 64) String> confirmers
) {
}
