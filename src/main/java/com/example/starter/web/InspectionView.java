package com.example.starter.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单项检验记录视图（田间纯度或室内发芽率通用）。
 *
 * @param kind         检验类别：FIELD 田间纯度/LAB 室内发芽率
 * @param round        检验轮次：1 首检，2 复检
 * @param value        检验值（百分比）：纯度或发芽率
 * @param passOriginal 是否达到原种门槛
 * @param passBreeder  是否达到良种门槛
 * @param retestable   不合格可复检标记
 * @param inspector    检验员（合成数据）
 * @param inspectedAt  检验时间（UTC）
 */
public record InspectionView(
        String kind,
        int round,
        BigDecimal value,
        boolean passOriginal,
        boolean passBreeder,
        boolean retestable,
        String inspector,
        Instant inspectedAt) {
}
