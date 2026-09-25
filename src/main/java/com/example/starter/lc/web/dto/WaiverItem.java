package com.example.starter.lc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 申请人放弃不符点声明中的条目，按“单据类型+不符点代码”定位已列明不符点。
 */
public record WaiverItem(
        @NotBlank String docType,
        @NotBlank String code) {
}
