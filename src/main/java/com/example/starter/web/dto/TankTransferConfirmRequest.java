package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨罐调拨执行确认：两名不同交接人（申请时登记）确认实际量等于申请量及合成凭据后，
 * 同事务扣源余额、加目标余额并释放双侧预占。
 *
 * @param transferId      调拨单ID
 * @param requestId       幂等键
 * @param expectedVersion 调拨单乐观锁期望版本
 * @param actualQtyKg     实际调拨量 kg，须等于申请量，至多三位小数
 * @param credentialNo    合成凭据号
 * @param confirmedAt     确认时刻（UTC）
 */
public record TankTransferConfirmRequest(
        @NotNull Long transferId,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin("0.001") BigDecimal actualQtyKg,
        @NotBlank String credentialNo,
        @NotNull Instant confirmedAt) {
}
