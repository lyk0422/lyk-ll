package com.example.starter.ili.persistence;

import com.example.starter.ili.model.RunSide;

import java.math.BigDecimal;

/**
 * defect_reading 表行记录。
 *
 * @param id         读数主键
 * @param batchId    所属批次
 * @param runSide    来源回合
 * @param readingKey 客户端读数键
 * @param stationM   绝对里程，单位米
 * @param depthPct   深度，占壁厚百分比
 * @param matched    是否已进入匹配对
 */
public record ReadingRow(long id, long batchId, RunSide runSide, String readingKey,
                         BigDecimal stationM, BigDecimal depthPct, boolean matched) {
}
