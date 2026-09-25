package com.example.starter.dto;

import java.math.BigDecimal;

/**
 * 评估明细中的单单元温度分布与失衡分类。
 *
 * @param unitCode       单元编码
 * @param returnTemp     参与评估的回水温度，单位:℃
 * @param deviation      相对全站平均回水温度的偏差，单位:℃
 * @param imbalanceType  平衡状态：BALANCED / UNDER_FLOW（欠流，回水温度偏低，需开大阀门）/ OVER_FLOW（过流，回水温度偏高，需关小阀门）
 */
public record UnitEvaluation(
        String unitCode,
        BigDecimal returnTemp,
        BigDecimal deviation,
        String imbalanceType
) {
}
