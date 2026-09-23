package com.example.starter.signing.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 建包请求：当前文档摘要、UTC 到期时间与有序签署步骤。
 *
 * @param documentHash 当前修订（修订号 1）文档 SHA-256 十六进制摘要
 * @param expiresAt    UTC 到期时间
 * @param steps        有序步骤；同一步骤的 signers 为并行签署人，全部完成才开放下一步
 */
public record CreatePackageRequest(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String documentHash,
        @NotNull Instant expiresAt,
        @NotEmpty @Valid List<StepSpec> steps) {

    /**
     * 单个签署步骤定义。
     *
     * @param stepOrder 步骤序号，从 1 开始且必须连续
     * @param signers   并行签署人列表，同一步骤内不得重复
     */
    public record StepSpec(
            @NotNull Integer stepOrder,
            @NotEmpty List<@NotBlank String> signers) {
    }
}
