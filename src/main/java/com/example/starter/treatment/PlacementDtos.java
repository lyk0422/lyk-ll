package com.example.starter.treatment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
     * @param batchId 投放批号，可空；空表示未启用批号管理的历史投放（保留原语义）
     * @param dosage 用量，单位同药剂
     * @param point 投放点位
     * @param registrar 登记人
     */
    public record PlacementRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "药剂不能为空") Long pesticideId,
            Long batchId,
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
     * 补投请求：一对一替代本链被召回投放，用量沿用被召回投放（相等），
     * 须双人签认且使用未召回、未过期、库存足够的同药剂批号。
     *
     * @param requestId 幂等请求标识
     * @param batchId 替代批号，须与被召回投放同药剂
     * @param firstRegistrar 第一登记人
     * @param secondRegistrar 第二登记人，须与第一登记人不同
     * @param expectedVersion 被召回投放的乐观锁版本，过期返回 409
     */
    public record ReplacementRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotNull(message = "替代批号不能为空") Long batchId,
            @NotBlank(message = "第一登记人不能为空") String firstRegistrar,
            @NotBlank(message = "第二登记人不能为空") String secondRegistrar,
            @NotNull(message = "expectedVersion 不能为空")
            @PositiveOrZero(message = "expectedVersion 不合法") Integer expectedVersion) {
    }

    /**
     * 投放响应。
     *
     * @param placementId 投放记录主键
     * @param status 状态：PENDING 待复核 / EFFECTIVE 已生效
     */
    public record PlacementResponse(long placementId, String status) {
    }

    /**
     * 投放详情响应：含批号、召回状态与补投对应关系；时间字段均为 UTC，空表示尚未发生。
     *
     * @param recallStatus NONE 未召回 / RECALLED 已召回待补投 / REPLACED 已补投
     * @param replacesPlacementId 本记录替代的被召回投放，空表示非补投记录
     * @param replacedByPlacementId 替代本记录的补投投放，空表示尚未补投
     */
    public record PlacementDetailResponse(long placementId, long caseId, long pesticideId,
            Long batchId, BigDecimal dosage, String point, String status, String recallStatus,
            Long replacesPlacementId, Long replacedByPlacementId, int version,
            String firstRegistrar, LocalDateTime firstRegisteredAt,
            String secondRegistrar, LocalDateTime secondRegisteredAt, LocalDateTime placedAt) {
    }
}
