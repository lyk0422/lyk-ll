package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 发车前提前解绑响应。
 *
 * @param bindingId       被解除的绑定ID
 * @param jobId           受控作业ID
 * @param deviceNo        装置编号
 * @param unboundAt       解绑时刻（UTC）
 * @param revokedConfirmations 同时撤销的一致性确认条数
 * @param deviceVersion   解绑后的装置版本（恢复空闲）
 */
public record TailUnbindResponse(
        long bindingId,
        long jobId,
        String deviceNo,
        Instant unboundAt,
        int revokedConfirmations,
        int deviceVersion) {
}
