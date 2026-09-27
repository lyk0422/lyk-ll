package com.example.starter.repo;

import com.example.starter.domain.TailReleaseType;

import java.time.Instant;

/**
 * 列尾装置绑定历史行记录；提前解绑或销记仅置解除信息，历史全部保留。
 *
 * @param id             绑定主键
 * @param deviceId       列尾装置ID
 * @param deviceNo       绑定时的装置编号快照
 * @param jobId          受控作业ID
 * @param tailCarNo      绑定时冻结的当前编组尾车号
 * @param consistVersion 绑定时冻结的编组版本
 * @param boundAt        绑定时刻（UTC，请求方精确时刻）
 * @param releasedAt     解除时刻（UTC）；NULL 表示生效中
 * @param releaseType    解除类型：UNBIND/CLOSURE；NULL 未解除
 */
public record TailBindingRow(
        long id,
        long deviceId,
        String deviceNo,
        long jobId,
        String tailCarNo,
        int consistVersion,
        Instant boundAt,
        Instant releasedAt,
        TailReleaseType releaseType) {

    /** 绑定是否仍生效（未提前解绑、未销记释放）。 */
    public boolean active() {
        return releasedAt == null;
    }
}
