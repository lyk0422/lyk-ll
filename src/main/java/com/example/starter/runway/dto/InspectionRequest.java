package com.example.starter.runway.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 巡检评估请求。
 *
 * @param requestId          幂等请求号
 * @param inspectorCount     巡检人数；低能见度（RVR 低于阈值）须双人执行，单人记录不生效
 * @param rvrMeters          合成跑道视程 RVR（米）
 * @param frictionCoefficient 道面摩擦系数；低于 0.35 须持续关闭，恰 0.35 合格
 * @param assessment         评估结论: CLEAR_TO_REOPEN 清除即可恢复 / REPAIR_REQUIRED 修复后恢复
 * @param startedAt          巡检开始时间(UTC)，为空取服务端当前时间
 */
public record InspectionRequest(
        @NotBlank String requestId,
        @Min(1) int inspectorCount,
        @Min(0) int rvrMeters,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal frictionCoefficient,
        @NotBlank @Pattern(regexp = "CLEAR_TO_REOPEN|REPAIR_REQUIRED", message = "评估结论仅支持 CLEAR_TO_REOPEN/REPAIR_REQUIRED") String assessment,
        Instant startedAt) {
}
