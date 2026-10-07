package com.example.starter.repo;

import com.example.starter.domain.DispositionStatus;

import java.time.Instant;

/**
 * 途中异常处置单行记录。
 *
 * @param id                 处置单主键
 * @param jobId              所属作业ID
 * @param status             处置状态：ACTIVE阻断中/RESUMED已续行结束
 * @param version            处置版本，自1起；每次成功修改加1
 * @param consistVersion     登记时冻结的编组版本（原发车证据）
 * @param frozenTailCarNo    登记时冻结的尾车车辆号（原发车证据）
 * @param frozenEotBindingId 登记时冻结的生效列尾绑定ID；null表示未绑定列尾装置
 * @param reason             途中异常说明（合成数据）
 * @param activeJobId        ACTIVE时等于作业ID，RESUMED为null
 * @param resumedAt          续行结束时刻（UTC）；null表示仍阻断中
 * @param createdAt          创建时间（UTC）
 */
public record DispositionRow(
        long id,
        long jobId,
        DispositionStatus status,
        int version,
        int consistVersion,
        String frozenTailCarNo,
        Long frozenEotBindingId,
        String reason,
        Long activeJobId,
        Instant resumedAt,
        Instant createdAt) {
}
