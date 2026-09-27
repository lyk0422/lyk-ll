package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 补液单双人交接确认请求：唯一运输批号 + 数量 + 合成验收浓度，两名不同交接人共同确认。
 *
 * @param stationCode            门站编码
 * @param requestId              幂等键
 * @param expectedVersion        期望的储罐当前版本号，不一致返回 409
 * @param transportBatchNo       唯一运输批号，重复批号拒绝入账
 * @param quantityKg             补液数量 kg（三位小数，入账后余额不得超容量）
 * @param acceptConcentrationMgL 合成验收浓度 mg/L，须在许可闭区间 [80.000, 120.000]
 * @param handlers               两名交接人工号集合（恰 2 人且互不相同）
 * @param confirmedAt            双人共同确认精确时刻（业务时间，Asia/Shanghai）
 */
public record RefillRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String transportBatchNo,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantityKg,
        @NotNull @DecimalMin("0") BigDecimal acceptConcentrationMgL,
        @NotNull @Size(min = 2, max = 2) List<@NotBlank String> handlers,
        @NotNull LocalDateTime confirmedAt) {
}
