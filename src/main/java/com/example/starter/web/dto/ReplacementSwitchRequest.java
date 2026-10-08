package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 热备切换双确认请求。到齐后由两个不同合成操作员各自确认；不足两人或缺车不释放旧车。
 *
 * @param requestId       幂等请求号
 * @param expectedVersion 期望的接替单当前版本号（不符409）
 * @param confirmer       合成操作员，同一人不可二次确认
 * @param confirmedAt     UTC精确确认时刻
 */
public record ReplacementSwitchRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String confirmer,
        @NotNull Instant confirmedAt) {
}
