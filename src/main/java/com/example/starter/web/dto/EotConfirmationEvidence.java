package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 列尾一致性确认证据视图。
 *
 * @param confirmationId  确认主键
 * @param bindingId       所属绑定ID
 * @param deviceNo        装置编号
 * @param tailCarNo       确认的尾车车辆号
 * @param consistVersion  确认的编组版本
 * @param confirmer1      确认人1
 * @param confirmer2      确认人2
 * @param valid           是否仍有效
 * @param requestId       幂等请求键
 * @param createdAt       确认时间（UTC）
 */
public record EotConfirmationEvidence(
        long confirmationId,
        long bindingId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        boolean valid,
        String requestId,
        Instant createdAt) {
}
