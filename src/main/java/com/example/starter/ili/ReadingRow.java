package com.example.starter.ili;

import java.math.BigDecimal;

/**
 * ili_defect_reading 表行：某一回合在管段绝对里程处的一次缺陷深度记录。
 *
 * @param id           读数主键
 * @param batchId      所属批次主键
 * @param runSide      读数所属回合：OLD=上一回合，NEW=本回合
 * @param defectRef    检测方缺陷编号，同批次同回合内唯一
 * @param absPositionM 缺陷绝对里程，单位米
 * @param depthPct     缺陷深度，占壁厚百分比，取值 0~100
 */
public record ReadingRow(
        long id,
        long batchId,
        RunSide runSide,
        String defectRef,
        BigDecimal absPositionM,
        BigDecimal depthPct) {
}
