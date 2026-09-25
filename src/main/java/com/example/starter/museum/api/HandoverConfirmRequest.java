package com.example.starter.museum.api;

import com.example.starter.museum.domain.Party;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 点交确认请求。type=OUTBOUND 出库点交 / RETURN 归还点交；
 * party=LENDER 借出方 / BORROWER 借入方，双方在同一点交单确认才生效。
 */
public record HandoverConfirmRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo,
        @NotNull HandoverTypeDto type,
        @NotNull Party party
) {
}
