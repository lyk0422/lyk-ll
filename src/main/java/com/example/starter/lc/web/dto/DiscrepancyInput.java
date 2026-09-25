package com.example.starter.lc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 拒付通知中的不符点条目。
 *
 * @param docType     不符点所在单据类型
 * @param code        不符点代码
 * @param description 不符点描述
 */
public record DiscrepancyInput(
        @NotBlank String docType,
        @NotBlank String code,
        String description) {
}
