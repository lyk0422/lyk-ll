package com.example.starter.legality.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 延误上报处理结果。
 *
 * @param late                  封板后补交为 true（迟到留痕，不推翻已封板结论）
 * @param accepted              延长是否被受理（重放或迟到留痕亦返回首次/既有状态）
 * @param idempotentReplay      是否同键重放
 * @param result                关联值勤记录当前结论
 * @param reasons               重新核算后（或既有）的违规原因
 */
public record DelayResponse(
        String requestId,
        String dutyRequestId,
        int extensionMinutes,
        int totalExtensionMinutes,
        Instant dutyEnd,
        boolean late,
        boolean accepted,
        String result,
        List<String> reasons,
        boolean idempotentReplay
) {
}
