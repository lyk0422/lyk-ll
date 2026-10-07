package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * 包装标签印次登记请求。
 *
 * <p>印次绑定服务端当前已生效备案的具体修订；儿童印次必须携带各年龄档
 * 独立标识摘要（bandDigests），逐档与备案受理冻结的标识文本一致。</p>
 *
 * @param requestId    幂等键；指纹覆盖 requestId、完整参数与分档集合
 * @param printRunNo   印次号，全局唯一
 * @param productCode  产品编码（须存在已生效备案）
 * @param labelDigest  标签文本摘要（与备案一致；儿童为全局摘要，不能代替分档摘要）
 * @param bandDigests  儿童印次各年龄档独立标识摘要（bandCode -&gt; digest）；成人印次不得携带
 * @param printedCount 印刷总张数（正整数）
 */
public record RegisterPrintRunRequest(
        @NotBlank String requestId,
        @NotBlank String printRunNo,
        @NotBlank String productCode,
        @NotBlank String labelDigest,
        Map<String, String> bandDigests,
        @NotNull @Positive Integer printedCount) {
}
