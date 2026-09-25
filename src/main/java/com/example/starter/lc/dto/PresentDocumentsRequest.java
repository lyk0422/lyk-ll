package com.example.starter.lc.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 受益人交单请求；拒付后改单重交请使用新 requestId，视为新交单批次。
 *
 * @param requestId   幂等键
 * @param lcNo        信用证编号
 * @param drawAmount  本批支款金额（币种元）
 * @param presentedAt 银行收到单据时刻（UTC），为空取服务当前时间
 * @param documents   随附单据清单
 */
public record PresentDocumentsRequest(
        @NotBlank String requestId,
        @NotBlank String lcNo,
        @NotNull @DecimalMin("0.01") BigDecimal drawAmount,
        Instant presentedAt,
        @NotEmpty @Valid List<DocumentItem> documents) {
}
