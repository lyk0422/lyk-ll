package com.example.starter.lc.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

/**
 * 开证申请人放弃不符点声明；声明必须覆盖全部已列不符点，部分放弃无效。
 *
 * @param requestId     幂等键
 * @param batchNo       交单批次号
 * @param applicant     声明放弃的开证申请人（须与信用证申请人一致）
 * @param waivedAt      声明时刻（UTC），为空取服务当前时间
 * @param discrepancies 声明放弃的不符点清单（须与拒付通知所列完全一致）
 */
public record WaiveDiscrepanciesRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotBlank String applicant,
        Instant waivedAt,
        @NotEmpty @Valid List<DiscrepancyItem> discrepancies) {
}
