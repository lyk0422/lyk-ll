package com.example.starter.lc.web.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 受益人交单请求（首交与改单重交共用；改单重交填 supersedesPresentationNo）。
 *
 * @param requestId                   幂等键
 * @param lcNo                        信用证编号
 * @param presentationNo              本次交单批次业务编号
 * @param supersedesPresentationNo    改单重交时上一拒付批次编号；首交为空
 * @param drawAmount                  本批次索偿金额
 * @param docs                        交单单据清单
 */
public record PresentRequest(
        @NotBlank String requestId,
        @NotBlank String lcNo,
        @NotBlank String presentationNo,
        String supersedesPresentationNo,
        @NotNull @Positive BigDecimal drawAmount,
        @NotEmpty @Valid List<DocItem> docs) {
}
