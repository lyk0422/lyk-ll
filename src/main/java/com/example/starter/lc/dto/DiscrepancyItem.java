package com.example.starter.lc.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 不符点条目，按单据类型归类。
 *
 * @param docType     不符点对应单据类型
 * @param description 不符点描述
 */
public record DiscrepancyItem(
        @NotBlank String docType,
        @NotBlank String description) {
}
