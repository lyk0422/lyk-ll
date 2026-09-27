package com.example.starter.damage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

/**
 * 修箱批复前密封询价流程请求报文集合。
 * 所有写操作均携带 requestId 用于幂等；新增/修改均携带 expectedVersion：
 * 版本从 1 开始，创建首个版本传 0，成功变更后版本加 1。
 * 所有截止时刻与资格有效期均为 UTC 精确时刻（{@link Instant}）。
 */
public final class InquiryRequests {

    private InquiryRequests() {
    }

    /** 合成供应商创建请求；methods 为适用工法代码集合 */
    public record CreateSupplierRequest(
            @NotBlank String requestId,
            @NotBlank String supplierNo,
            @NotBlank String name,
            @NotEmpty List<@NotBlank String> methods) {
    }

    /**
     * 授予/续期有效资格版本（追加 ACTIVE 版本）。
     * expectedVersion 为当前最新资格版本号，首次授予传 0；成功后产生版本号 expectedVersion+1。
     * validTo 为 null 表示长期有效。
     */
    public record QualificationGrantRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull Instant validFrom,
            Instant validTo) {
    }

    /** 撤销资格请求；追加 REVOKED 新版本，撤销时刻即新版本 validFrom/validTo */
    public record QualificationRevokeRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion,
            @NotNull Instant revokedAt) {
    }

    /** 询价单行：锚定一个案件残损项并给出允许工法集合 */
    public record InquiryItemSpec(
            @NotNull @Positive Long damageItemId,
            @NotEmpty List<@NotBlank String> methods) {
    }

    /**
     * 发布密封询价版本。
     * expectedVersion 为该案件当前询价序号数：首次发布传 0，零中标再发布传已存在序号数。
     * items 必须恰好覆盖案件全部残损项各一次。
     */
    public record PublishInquiryRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull Instant deadline,
            @NotEmpty List<@Valid InquiryItemSpec> items) {
    }

    /** 报价单行：行号对齐询价行，金额为正整数分 */
    public record BidLineInput(
            @NotNull @Positive Integer lineNo,
            @NotBlank String methodCode,
            @NotNull @Positive Long amountCents) {
    }

    /**
     * 提交/修订整版报价。
     * expectedVersion：首次报价传 0，修订传当前报价版本号；成功后产生新版本号。
     * 报价必须按行号精确覆盖询价全部行，任一不符整版拒绝。
     */
    public record SubmitBidRequest(
            @NotBlank String requestId,
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotEmpty List<@Valid BidLineInput> lines) {
    }

    /** 开标请求；仅在 now 不早于 UTC 截止时刻时可开标，重复开标返回首次冻结清单 */
    public record OpenBidsRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion) {
    }

    /** 中标报价复核请求；同一开标同一复核人不得重复，两名不同复核人齐自动转批复 */
    public record ReviewRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }
}
