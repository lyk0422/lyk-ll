package com.example.starter.cosmetic.child.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 适用年龄标识请求条目（按档位独立）。
 *
 * @param bandCode  标识适用档位编码（标识适用范围即该档位年龄区间）
 * @param labelText 标识文本
 */
public record ChildLabelRequest(
        @NotBlank String bandCode,
        @NotBlank String labelText) {
}
