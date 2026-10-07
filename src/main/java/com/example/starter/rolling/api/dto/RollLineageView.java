package com.example.starter.rolling.api.dto;

import java.util.List;

/**
 * 物理辊代次链与各代独立台账的组合查询视图。
 *
 * @param physical   物理辊聚合视图
 * @param generations 代次链（按代次号升序），每代独立登记辊号与磨后直径
 * @param ledgers     各代独立寿命台账（与 generations 同序），旧代里程、实绩、禁排与冻结结论保留
 */
public record RollLineageView(
        PhysicalRollView physical,
        List<RollGenerationView> generations,
        List<RollView> ledgers) {
}
