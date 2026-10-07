package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单个分龄档的标识摘要项（儿童印次登记请求/响应共用）。
 *
 * @param bandCode    分龄档编码
 * @param labelDigest 该档适用年龄标识文本摘要(SHA-256，结构化无歧义)
 */
public record BandDigestItem(
        @NotBlank String bandCode,
        @NotBlank String labelDigest) {
}
