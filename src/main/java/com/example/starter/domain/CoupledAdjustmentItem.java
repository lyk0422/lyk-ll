package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 联调逐阀明细：联调前后开度保留，可复算净加权开度及逐阀额度。
 *
 * @param id         主键
 * @param coupledId  所属联调记录 ID
 * @param valveId    成员阀门 ID
 * @param unsealId   铅封阀消费的解封单 ID；未封阀为 null
 * @param taskId     实际变化阀生成的调节任务 ID；不变阀为 null（不消费次数与额度）
 * @param weight     联调时冻结的阀门权重快照，正整数 1~10
 * @param oldOpening 联调前开度，单位 %
 * @param newOpening 联调后目标开度，单位 %，0~100 整数百分点
 * @param createdAt  明细生成时刻（UTC）
 */
public record CoupledAdjustmentItem(long id, long coupledId, long valveId, Long unsealId, Long taskId,
                                    int weight, BigDecimal oldOpening, BigDecimal newOpening,
                                    Instant createdAt) {
}
