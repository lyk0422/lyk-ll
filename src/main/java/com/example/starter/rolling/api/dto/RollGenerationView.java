package com.example.starter.rolling.api.dto;

import java.math.BigDecimal;

/**
 * 物理辊的单个代次视图。
 *
 * @param physicalNo             所属物理辊编号
 * @param generationNo           代次号（从 1 起）
 * @param rollNo                 该代辊号
 * @param diameterMm             该代磨后直径（毫米，两位小数）；首代绑定已有辊号时为 null
 * @param successorApplicationId 该代成功后继返修申请 id；null 表示尚无成功后继
 */
public record RollGenerationView(
        String physicalNo,
        Integer generationNo,
        String rollNo,
        BigDecimal diameterMm,
        Long successorApplicationId) {
}
