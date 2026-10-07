package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 联调逐阀明细：联调前后开度与权重留痕，可复算净加权开度及逐阀额度。
 *
 * @param id         主键
 * @param coupledId  所属联调单 ID
 * @param valveId    成员阀门 ID
 * @param weight     联调时冻结的阀门权重，正整数 1~10
 * @param oldOpening 联调前开度，单位 %，0~100 整数百分点
 * @param newOpening 联调目标开度，单位 %；与旧值相等表示本阀不变
 * @param unsealId   铅封阀使用的解封单 ID；未封阀为 null
 * @param taskId     实际变化阀生成的调节任务 ID（沿用原温差评估及归档）；不变阀为 null
 */
public record CoupledAdjustmentItem(long id, long coupledId, long valveId, int weight,
                                    BigDecimal oldOpening, BigDecimal newOpening,
                                    Long unsealId, Long taskId) {
}
