package com.example.starter.repo;

import java.time.Instant;

/**
 * 途中试验与列尾一致性双人确认行记录。
 *
 * @param id             确认主键
 * @param dispositionId  所属处置单ID
 * @param jobId          所属作业ID
 * @param consistVersion 确认时编组版本
 * @param tailCarNo      确认时当前编组尾车车辆号
 * @param confirmer1     确认人1
 * @param confirmer2     确认人2
 * @param valid          是否仍有效
 * @param createdAt      创建时间（UTC）
 */
public record EnRouteConfirmationRow(
        long id,
        long dispositionId,
        long jobId,
        int consistVersion,
        String tailCarNo,
        String confirmer1,
        String confirmer2,
        boolean valid,
        Instant createdAt) {
}
