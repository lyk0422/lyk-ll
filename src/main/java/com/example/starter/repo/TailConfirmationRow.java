package com.example.starter.repo;

import java.time.LocalDateTime;

/**
 * 列尾一致性确认行记录。
 *
 * @param id             确认主键
 * @param bindingId      所属绑定主键
 * @param jobId          所属作业ID
 * @param consistVersion 确认的编组版本
 * @param tailCarNo      确认的尾车车辆号
 * @param confirmer      确认人（合成数据）
 * @param valid          是否有效：摘挂变更或解绑后失效
 * @param createdAt      确认时间（JVM 时区）
 */
public record TailConfirmationRow(
        long id,
        long bindingId,
        long jobId,
        int consistVersion,
        String tailCarNo,
        String confirmer,
        boolean valid,
        LocalDateTime createdAt) {
}
