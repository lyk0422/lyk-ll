package com.example.starter.damage.dto;

import com.example.starter.damage.domain.Responsibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
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
}
