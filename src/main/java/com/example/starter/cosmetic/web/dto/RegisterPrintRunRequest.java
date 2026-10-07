package com.example.starter.cosmetic.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 包装标签印次登记请求。
 *
 * <p>印次绑定产品当前有效备案的具体修订；标签文本摘要须与备案受理冻结内容
 * 一致（成人=备案级摘要；儿童=逐档摘要集合的结构化摘要），儿童印次必须逐档
 * 提供独立标识摘要，缺档/摘要错/未生效备案拒绝。</p>
 *
 * @param requestId    幂等键；指纹覆盖 requestId 与全部业务字段（含分档集合与时刻）
 * @param printRunNo   印次号（全局唯一）
 * @param productCode  产品编码
 * @param printedCount 印刷总张数（正整数）
 * @param labelDigest  标签文本摘要；儿童印次为逐档摘要集合的结构化摘要
 * @param bandDigests  儿童印次逐档标识摘要；成人印次必须为 null 或空
 * @param atUtc        业务时刻(UTC)，可空（空取当前时刻）；参与幂等指纹
 */
public record RegisterPrintRunRequest(
        @NotBlank String requestId,
        @NotBlank String printRunNo,
        @NotBlank String productCode,
        @NotNull @Min(1) Integer printedCount,
        @NotBlank String labelDigest,
        @Valid List<BandDigestItem> bandDigests,
        Instant atUtc) {
}
