package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 补液单请求：唯一运输批号，两名不同交接人共同确认后原子入账。
 *
 * @param tankCode          储罐编码
 * @param requestId         幂等键
 * @param expectedVersion   储罐乐观锁期望版本
 * @param transportBatchNo  唯一运输批号，重复批号拒绝入账
 * @param quantityKg        补液数量 kg，须为正，至多三位小数；加入后不得超容量
 * @param concentrationMgM3 合成验收浓度 mg/m3，须在许可闭区间内
 * @param operators         交接人工号集合，恰两名且互不相同
 * @param receivedAt        验收时刻（业务时间，精确时刻参与指纹）
 */
public record TankRefillRequest(
        @NotBlank String tankCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String transportBatchNo,
        @NotNull @DecimalMin("0.001") BigDecimal quantityKg,
        @NotNull @DecimalMin("0.0") BigDecimal concentrationMgM3,
        @NotNull @Size(min = 2, max = 2) List<@NotBlank String> operators,
        @NotNull LocalDateTime receivedAt) {
}
