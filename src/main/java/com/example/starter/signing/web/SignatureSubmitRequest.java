package com.example.starter.signing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 签名提交请求。
 *
 * @param requestId      幂等请求键；同键同完整参数返回首次结果，同键异参返回 409
 * @param signer         签署人标识
 * @param revision       提交方针对的文档修订号，必须等于签署包当前修订号
 * @param documentHash   提交方计算的文档 SHA-256 摘要，必须与当前修订摘要一致
 * @param expectedVersion 期望版本（当前修订号），与 revision 一同用于乐观并发校验
 * @param signature       合成签名值，由本地验证适配器校验
 */
public record SignatureSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String signer,
        @NotNull Integer revision,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String documentHash,
        @NotNull Integer expectedVersion,
        @NotBlank String signature) {
}
