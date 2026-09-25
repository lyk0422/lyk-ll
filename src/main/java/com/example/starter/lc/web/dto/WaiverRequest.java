package com.example.starter.lc.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 开证申请人放弃不符点声明。须覆盖拒付通知中全部不符点，部分放弃视为无效。
 */
public record WaiverRequest(
        @NotBlank String requestId,
        @NotBlank String presentationNo,
        @NotEmpty @Valid List<WaiverItem> waivedDiscrepancies) {
}
