package com.example.starter.treatment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 治理投放接口报文。
 */
public final class PlacementDtos {

    private PlacementDtos() {
    }

    /**
     * 投放登记请求（第一登记人）。
     *
     * @param requestId 幂等请求标识
     * @param pesticideId 药剂主键
     * @param dosage 用量，单位同药剂
     * @param point 投放点位
     * @param registrar 登记人
     */
    public record PlacementRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "药剂不能为空") Long pesticideId,
            @NotNull(message = "用量不能为空") @Positive(message = "用量须为正数") BigDecimal dosage,
            @NotBlank(message = "投放点位不能为空") String point,
            @NotBlank(message = "登记人不能为空") String registrar) {
    }

    /**
     * 投放复核请求（第二登记人，须与第一登记人不同且信息一致）。
     *
     * @param requestId 幂等请求标识
     * @param registrar 复核登记人
     * @param pesticideId 药剂主键，须与首次登记一致
     * @param dosage 用量，须与首次登记一致
     * @param point 投放点位，须与首次登记一致
     */
    public record PlacementConfirmRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "复核登记人不能为空") String registrar,
            @NotNull(message = "药剂不能为空") Long pesticideId,
            @NotNull(message = "用量不能为空") @Positive(message = "用量须为正数") BigDecimal dosage,
            @NotBlank(message = "投放点位不能为空") String point) {
    }

    /**
     * 投放响应。
     *
     * @param placementId 投放记录主键
     * @param status 状态：PENDING 待复核 / EFFECTIVE 已生效
     */
    public record PlacementResponse(long placementId, String status) {
    }
}
