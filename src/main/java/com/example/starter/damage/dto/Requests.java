package com.example.starter.damage.dto;

import com.example.starter.damage.domain.Responsibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

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

    // ==================== 密封询价与比价流程 ====================

    /** 供应商登记请求：编号唯一，携带适用工法与首个资格版本的有效期（UTC，含头不含尾） */
    public record SupplierCreateRequest(
            @NotBlank String requestId,
            @NotBlank String supplierCode,
            @NotBlank String name,
            @NotEmpty List<@NotBlank String> methods,
            @NotNull LocalDateTime validFrom,
            @NotNull LocalDateTime validTo) {
    }

    /**
     * 供应商资格续期请求：expectedVersion 为供应商当前版本（初始 1），
     * 成功后供应商版本与资格版本号同时加 1。
     */
    public record QualificationRenewRequest(
            @NotBlank String requestId,
            @Positive int expectedVersion,
            @NotNull LocalDateTime validFrom,
            @NotNull LocalDateTime validTo) {
    }

    /** 撤销供应商资格请求：成功后供应商版本加 1；批复后撤销只形成风险事件 */
    public record QualificationRevokeRequest(
            @NotBlank String requestId,
            @Positive int expectedVersion,
            @NotBlank String reason) {
    }

    /** 询价发布请求中每项允许的工法（非空），按案件残损项顺序一一固定 */
    public record InquiryItemSpec(
            long damageItemId,
            @NotEmpty List<@NotBlank String> allowedMethods) {
    }

    /**
     * 发布询价版本请求：expectedVersion 为该案件当前已有询价版本数（首版传 0），
     * 截止时刻为 UTC 精确时刻；成功后新版本号 = expectedVersion + 1（版本号从 1 开始）。
     */
    public record InquiryPublishRequest(
            @NotBlank String requestId,
            @PositiveOrZero int expectedVersion,
            @NotNull LocalDateTime deadline,
            @NotEmpty @Valid List<InquiryItemSpec> items) {
    }

    /** 报价逐项明细：金额为正整数分，工法必须是该项允许工法且供应商适用 */
    public record BidItemInput(
            long inquiryItemId,
            @NotBlank String methodCode,
            @Positive long amountCents) {
    }

    /**
     * 提交/修订报价请求：expectedVersion 为该供应商在本询价版本的当前报价修订号，
     * 首次投标传 0；成功后修订号从 1 开始并随修订加 1。整版必须覆盖全部询价项。
     */
    public record BidSubmitRequest(
            @NotBlank String requestId,
            @NotBlank String supplierCode,
            @PositiveOrZero int expectedVersion,
            @NotEmpty @Valid List<BidItemInput> items) {
    }

    /** 开标请求：必须在截止时刻（不含）之后；成功冻结全部当前报价并按开标时刻资格比价 */
    public record BidOpenRequest(@NotBlank String requestId) {
    }

    /**
     * 双人不同复核请求：expectedVersion 为中标报价修订号（与开标清单一致），
     * 两位复核人非空且不同；成功后中标报价转原案修理批复并冻结全部版本。
     */
    public record AwardConfirmRequest(
            @NotBlank String requestId,
            @Positive int expectedVersion,
            @NotBlank String reviewer1,
            @NotBlank String reviewer2,
            LocalDate approvalDate) {
    }
}
