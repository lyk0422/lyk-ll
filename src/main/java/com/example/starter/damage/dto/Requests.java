package com.example.starter.damage.dto;

import com.example.starter.damage.domain.Responsibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 残损检验服务请求报文集合。所有写操作均携带 requestId 用于幂等。
 */
public final class Requests {

    private Requests() {
    }

    /** 到港登记请求：箱号与箱值为业务字段，参与幂等指纹 */
    public record RegisterRequest(
            @NotBlank String requestId,
            @NotBlank String containerNo,
            @NotNull @DecimalMin(value = "0.01", message = "箱值必须为正") BigDecimal containerValue) {
    }

    /** 残损项：按合成 IICL 代码登记，区分箱东/码头责任 */
    public record DamageItemInput(
            @NotBlank String iiclCode,
            @NotBlank String component,
            String description,
            @NotNull Responsibility responsibility,
            @NotNull @DecimalMin(value = "0.00", message = "修理费不得为负") BigDecimal repairCost) {
    }

    /** 检验登记请求 */
    public record SurveyRequest(
            @NotBlank String requestId,
            @NotEmpty List<@Valid DamageItemInput> items) {
    }

    /** 估价请求 */
    public record EstimateRequest(@NotBlank String requestId) {
    }

    /** 修理批复请求；approvalDate 为空时取当前 UTC 日期 */
    public record ApprovalRequest(
            @NotBlank String requestId,
            LocalDate approvalDate) {
    }

    /** 修理完工请求；completionDate 为空时取当前 UTC 日期 */
    public record CompletionRequest(
            @NotBlank String requestId,
            LocalDate completionDate) {
    }

    /** 复检请求 */
    public record ReinspectionRequest(
            @NotBlank String requestId,
            @NotNull Boolean passed) {
    }

    /** 结案（放行/全损）请求 */
    public record CloseRequest(@NotBlank String requestId) {
    }

    /** 检验照片登记请求 */
    public record PhotoRequest(@NotBlank String photoRef) {
    }

    /** 供应商登记请求：初始资格版本为 1，含适用工法与资格有效期（UTC 时刻） */
    public record SupplierCreateRequest(
            @NotBlank String requestId,
            @NotBlank String supplierNo,
            @NotBlank String name,
            @NotEmpty List<@NotBlank String> methods,
            @NotNull LocalDateTime validFrom,
            @NotNull LocalDateTime validUntil) {
    }

    /** 资格修订请求：expectedVersion 须等于当前版本，成功变更加 1 */
    public record QualificationUpdateRequest(
            @NotBlank String requestId,
            @NotNull Long expectedVersion,
            @NotEmpty List<@NotBlank String> methods,
            @NotNull LocalDateTime validFrom,
            @NotNull LocalDateTime validUntil) {
    }

    /** 资格撤销请求：定标后撤销只形成风险事件，不改历史中标证据 */
    public record QualificationRevokeRequest(
            @NotBlank String requestId,
            @NotNull Long expectedVersion) {
    }

    /** 询价项输入：残损项 ID 与该项允许工法集合 */
    public record InquiryItemInput(
            @NotNull Long itemId,
            @NotEmpty List<@NotBlank String> methods) {
    }

    /** 询价发布请求：固定残损项集合、每项允许工法与 UTC 报价截止时刻 */
    public record InquiryPublishRequest(
            @NotBlank String requestId,
            @NotEmpty List<@Valid InquiryItemInput> items,
            @NotNull LocalDateTime deadline) {
    }

    /** 报价行输入：金额为正整数分 */
    public record QuotationLineInput(
            @NotNull Long itemId,
            @NotBlank String method,
            @NotNull @Min(value = 1, message = "报价金额须为正整数分") Long amountCents) {
    }

    /** 报价提交请求：一供应商一询价版本只一个当前报价 */
    public record QuotationSubmitRequest(
            @NotBlank String requestId,
            @NotNull Long supplierId,
            @NotEmpty List<@Valid QuotationLineInput> lines) {
    }

    /** 报价修订请求：expectedVersion 须等于当前报价版本，修订保留历史 */
    public record QuotationReviseRequest(
            @NotBlank String requestId,
            @NotNull Long expectedVersion,
            @NotEmpty List<@Valid QuotationLineInput> lines) {
    }

    /** 开标请求：截止后开标，重复开标返回首次清单 */
    public record BidOpenRequest(@NotBlank String requestId) {
    }

    /** 定标复核请求：双人不同复核才转修理批复 */
    public record ReviewRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }
}
