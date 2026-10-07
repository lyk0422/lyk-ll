package com.example.starter.repo;

import java.time.Instant;

/**
 * 途中新试验与列尾一致性双人确认行记录。
 *
 * @param id             确认主键
 * @param incidentId     所属处置单ID
 * @param jobId          所属作业ID
 * @param tailCarNo      确认时的尾车车辆号
 * @param consistVersion 确认时的编组版本
 * @param confirmer1     共同确认人1
 * @param confirmer2     共同确认人2
 * @param valid          是否仍有效
 * @param requestId      幂等请求键
 * @param createdAt      创建时间（UTC）
 */
public record EnRouteConfirmationRow(
        long id,
        long incidentId,
        long jobId,
        String tailCarNo,
        int consistVersion,
        String confirmer1,
        String confirmer2,
        boolean valid,
        String requestId,
        Instant createdAt) {
}
