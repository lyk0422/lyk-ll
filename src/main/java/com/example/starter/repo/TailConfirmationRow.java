package com.example.starter.repo;

import java.time.Instant;

/**
 * 列尾装置与编组尾车一致性共同确认行记录（两名不同人员）。
 *
 * @param id                确认主键
 * @param bindingId         所属绑定ID
 * @param jobId             所属作业ID
 * @param deviceId          列尾装置ID
 * @param tailCarNo         确认时的尾车号
 * @param consistVersion    确认时的编组版本
 * @param confirmer1        第一名共同确认人员
 * @param confirmer2        第二名共同确认人员（与第一名不同）
 * @param confirmedAt       共同确认时刻（UTC，请求方精确时刻）
 * @param valid             是否仍有效
 * @param invalidatedAt     失效时刻（UTC）；NULL 仍有效
 * @param invalidatedReason 失效原因：CONSIST_CHANGED/SUPERSEDED/UNBIND/CLOSURE；NULL 仍有效
 */
public record TailConfirmationRow(
        long id,
        long bindingId,
        long jobId,
        long deviceId,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        Instant confirmedAt,
        boolean valid,
        Instant invalidatedAt,
        String invalidatedReason) {
}
