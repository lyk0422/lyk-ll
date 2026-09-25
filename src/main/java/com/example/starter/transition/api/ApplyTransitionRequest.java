package com.example.starter.transition.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 改品种申请请求；申请时即完成方式判定（直接切换/停机清洗/冲洗网部）。
 *
 * @param requestId     幂等键
 * @param machineId     纸机编号
 * @param orderNo       改品种单号（客户端指定，便于复算与对账）
 * @param fromGradeCode 改前品种
 * @param toGradeCode   改后品种
 */
public record ApplyTransitionRequest(
        @NotBlank String requestId,
        @NotBlank String machineId,
        @NotBlank String orderNo,
        @NotBlank String fromGradeCode,
        @NotBlank String toGradeCode
) {
}
