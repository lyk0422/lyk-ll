package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 列尾绑定证据视图：一条绑定历史及其全部一致性确认记录。
 *
 * @param bindingId       绑定主键
 * @param deviceId        装置主键
 * @param deviceNo        装置编号
 * @param tailCarNo       绑定时冻结的尾车车辆号
 * @param consistVersion  绑定时冻结的编组版本
 * @param status          绑定状态：ACTIVE/RELEASED
 * @param requestId       绑定幂等请求键
 * @param createdAt       绑定时间（UTC）
 * @param releasedAt      解除时间（UTC）；null 表示仍绑定中
 * @param confirmations   该绑定下全部一致性确认证据（含已失效记录）
 */
public record EotBindingEvidence(
        long bindingId,
        long deviceId,
        String deviceNo,
        String tailCarNo,
        int consistVersion,
        String status,
        String requestId,
        Instant createdAt,
        Instant releasedAt,
        List<EotConfirmationEvidence> confirmations) {
}
