package com.example.starter.wb.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集装器装配图提交请求：携带舱单与装配图双重期望版本，成功提交后装配图版本加 1。
 * 幂等指纹覆盖 requestId、双重版本、明细集合与精确提交时刻。
 *
 * @param expectedManifestVersion 期望的当前有效舱单版本号
 * @param expectedPlanVersion     期望的当前有效装配图版本；尚无装配图时为 0
 * @param submittedAt             精确提交时刻，Asia/Shanghai
 */
public record AssemblySubmitRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedManifestVersion,
        @NotNull Integer expectedPlanVersion,
        @NotEmpty List<@Valid AssemblyItemRequest> items,
        @NotBlank String operator,
        @NotNull LocalDateTime submittedAt
) {
}
