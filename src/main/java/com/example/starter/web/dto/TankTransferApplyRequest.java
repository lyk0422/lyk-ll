package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 跨罐调拨申请：原子预占源罐数量与目标罐空间。
 *
 * <p>幂等指纹覆盖 requestId 与全部业务字段：两罐、品种号、调拨量、
 * 运输批号、两名交接人与 UTC 申请时刻。
 *
 * @param sourceTankCode   源罐编码
 * @param targetTankCode   目标罐编码，须与源罐不同且同品种
 * @param varietyNo        调拨品种号，须与两罐登记的不可变品种号一致
 * @param requestId        幂等键
 * @param quantityKg       调拨量 kg，须为正，至多三位小数，超精度拒绝而非舍入
 * @param transportBatchNo 全局唯一运输批号，不能以另一个 requestId 再次入账
 * @param operators        交接人工号集合，恰两名且互不相同
 * @param appliedAt        申请时刻（UTC）
 */
public record TankTransferApplyRequest(
        @NotBlank String sourceTankCode,
        @NotBlank String targetTankCode,
        @NotBlank String varietyNo,
        @NotBlank String requestId,
        @NotNull @DecimalMin("0.001") BigDecimal quantityKg,
        @NotBlank String transportBatchNo,
        @NotNull @Size(min = 2, max = 2) List<@NotBlank String> operators,
        @NotNull Instant appliedAt) {
}
