package com.example.starter.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 掺烧方案申报请求。
 *
 * @param requestId       申报幂等键，指纹覆盖该键与全部业务字段
 * @param kilnId          窑口编码，同一窑口同一时刻仅允许一个执行中方案
 * @param licenseNo       绑定的危废经营许可证编号（合成许可库）
 * @param declaredHeatRatio 申报的替代燃料热量替代率，上限 0.4（恰 0.4 合法）
 * @param floorHeatRatio  降掺保底比例，超时升级时强制降至此值
 * @param fuels           入窑燃料组分（含常规燃料与替代燃料），按投料量加权计算氯硫
 */
public record DeclarePlanRequest(
        @NotBlank String requestId,
        @NotBlank String kilnId,
        @NotBlank String licenseNo,
        @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal declaredHeatRatio,
        @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal floorHeatRatio,
        @NotEmpty List<FuelComponentRequest> fuels) {
}
