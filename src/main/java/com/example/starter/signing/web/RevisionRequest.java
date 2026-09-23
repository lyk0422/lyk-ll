package com.example.starter.signing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 文档修订请求：仅在最终完成前允许，会使旧修订签名全部失效并重置到第一步。
 *
 * @param newDocumentHash 新修订文档 SHA-256 十六进制摘要
 */
public record RevisionRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String newDocumentHash) {
}
