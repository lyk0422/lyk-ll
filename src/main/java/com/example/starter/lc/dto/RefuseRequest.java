package com.example.starter.lc.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

/**
 * 银行拒付通知请求：必须一次性列明全部不符点，事后不得补充。
 *
 * @param requestId    幂等键
 * @param batchNo      交单批次号
 * @param refusedAt    拒付通知发出时刻（UTC），为空取服务当前时间；晚于截止时刻则拒付无效
 * @param discrepancies 全部不符点清单（至少一条）
 */
public record RefuseRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        Instant refusedAt,
        @NotEmpty @Valid List<DiscrepancyItem> discrepancies) {
}
