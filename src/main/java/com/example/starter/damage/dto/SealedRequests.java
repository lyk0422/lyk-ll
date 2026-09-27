package com.example.starter.damage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 修箱密封报价与供应商资格请求报文集合。
 * 所有新增写操作均携带 requestId（幂等）与 expectedVersion（乐观版本，从 1 开始）。
 * 时刻字段一律为 UTC。
 */
public final class SealedRequests {

    private SealedRequests() {
    }

    /**
     * 发布询价版本：固定残损项集合与每项允许工法、UTC 报价截止时刻。
     * expectedVersion 必须等于该案件下一个询价版本号（首次为 1）。
     */
    public record PublishInquiryRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull LocalDateTime deadline,
            @NotEmpty List<@Valid InquiryItemInput> items) {
    }

    /** 询价残损项：引用原案残损项 ID 及该项允许工法集合（顺序即报价项序号，从 1 开始） */
    public record InquiryItemInput(
            @NotNull Long itemId,
            @NotEmpty List<@NotBlank String> allowedMethods) {
    }

    /** 提交/修订报价：一供应商一询价版本仅一个当前报价，须按序号覆盖全部项；
     *  expectedVersion 为本次将产生的报价修订号（首次 1，修订 2…） */
    public record SubmitBidRequest(
            @NotBlank String requestId,
            @NotBlank String supplierNo,
            @NotNull Integer expectedVersion,
            @NotEmpty List<@Valid BidLineInput> lines) {
    }

    /** 报价分项：itemSeq 对应询价项序号，priceCents 为正整数分 */
    public record BidLineInput(
            @NotNull Integer itemSeq,
            @NotBlank String methodCode,
            @NotNull @Positive Long priceCents) {
    }

    /** 截止后开标请求 */
    public record OpenBidsRequest(@NotBlank String requestId) {
    }

    /** 中标双人不同复核请求；第二人复核通过即转原案修理批复 */
    public record ReviewRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }

    /** 供应商资格续期：新版本只追加，expectedVersion 为下一个资格版本号（首次为 1） */
    public record QualificationRenewRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull LocalDateTime validFrom,
            @NotNull LocalDateTime validUntil,
            @NotEmpty List<@NotBlank String> methods) {
    }

    /** 撤销供应商当前资格；批复后撤销只形成风险事件 */
    public record QualificationRevokeRequest(@NotBlank String requestId) {
    }
}
