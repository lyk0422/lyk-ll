package com.example.starter.carbon;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;

/**
 * 对外 API 请求/响应数据传输对象。数量单位均为 tCO2e。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /**
     * 创建企业请求（合成数据）。
     *
     * @param code 企业唯一编码
     * @param name 企业名称
     */
    public record EnterpriseCreateRequest(@NotBlank String code, @NotBlank String name) {
    }

    /**
     * 企业视图。
     */
    public record EnterpriseView(Long id, String code, String name) {
    }

    /**
     * 年度排放量核定请求。
     *
     * @param enterpriseCode 企业编码
     * @param complianceYear 履约年度
     * @param emissionAmount 核定排放量（tCO2e，正数）
     */
    public record EmissionAssessRequest(@NotBlank String enterpriseCode,
                                        @NotNull @Min(2000) Integer complianceYear,
                                        @NotNull @Positive Long emissionAmount) {
    }

    /**
     * 配额批次发放请求。
     *
     * @param enterpriseCode 企业编码
     * @param vintageYear    配额核发年度
     * @param amount         批次数量（tCO2e）
     * @param expiryDate     到期日（UTC 日期），扣减时到期日近者优先
     */
    public record AllowanceGrantRequest(@NotBlank String enterpriseCode,
                                        @NotNull @Min(2000) Integer vintageYear,
                                        @NotNull @Positive Long amount,
                                        @NotNull LocalDate expiryDate) {
    }

    /**
     * 抵消信用批次签发请求。
     *
     * @param enterpriseCode 企业编码
     * @param creditType     信用类型（如 CCER）
     * @param amount         批次数量（tCO2e）
     * @param expiryDate     到期日（UTC 日期）
     */
    public record OffsetCreditRequest(@NotBlank String enterpriseCode,
                                      @NotBlank String creditType,
                                      @NotNull @Positive Long amount,
                                      @NotNull LocalDate expiryDate) {
    }

    /**
     * 批次账户视图。
     *
     * @param status ACTIVE=可用，EXHAUSTED=已用尽
     */
    public record BatchView(Long id, Long totalAmount, Long remainingAmount,
                            LocalDate expiryDate, String status) {
    }

    /**
     * 清缴申请请求。组合明细（配额量+抵消量）参与幂等指纹。
     *
     * @param requestId       客户端幂等键
     * @param enterpriseCode  企业编码
     * @param complianceYear  履约年度
     * @param allowanceAmount 使用配额数量（tCO2e，可为 0）
     * @param offsetAmount    使用抵消信用数量（tCO2e，可为 0；累计不超过核定排放量 5%）
     */
    public record SurrenderApplyRequest(@NotBlank String requestId,
                                        @NotBlank String enterpriseCode,
                                        @NotNull @Min(2000) Integer complianceYear,
                                        @NotNull @PositiveOrZero Long allowanceAmount,
                                        @NotNull @PositiveOrZero Long offsetAmount) {
    }

    /**
     * 注销明细视图。
     *
     * @param sourceType ALLOWANCE=配额，OFFSET=抵消信用
     * @param status     CANCELLED=已注销（终态）
     */
    public record SurrenderItemView(String sourceType, Long batchId, Long amount, String status) {
    }

    /**
     * 清缴申请响应。
     *
     * @param idempotentReplay 是否为同键同参的幂等重放（返回首次注销清单）
     */
    public record SurrenderResponse(String requestId, String status,
                                    Long allowanceAmount, Long offsetAmount,
                                    String certificateNo,
                                    List<SurrenderItemView> items,
                                    boolean idempotentReplay) {
    }

    /**
     * 清缴凭证视图。凭证号唯一，重复查询结果稳定。
     */
    public record CertificateView(String certificateNo, String requestId,
                                  String enterpriseCode, Integer complianceYear,
                                  Long totalAmount, Long offsetAmount, String issuedAt) {
    }

    /**
     * 履约状态视图。
     *
     * @param status PENDING=履约中，COMPLIANT=已足额履约，NON_COMPLIANT=未履约
     */
    public record ComplianceStatusView(String enterpriseCode, Integer complianceYear, String status,
                                       Long emissionAmount, Long surrenderedTotal, Long offsetUsed) {
    }

    /**
     * 统一错误响应。
     */
    public record ErrorResponse(String code, String message) {
    }
}
