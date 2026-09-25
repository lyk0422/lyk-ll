package com.example.starter.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 浓度曲线复算点：由原始检测读数重新计算合格判定，不依赖首次处理的缓存结果。
 *
 * @param requestId         检测请求幂等键
 * @param detectedAt        检测时刻
 * @param gasFlowM3         时段流量 m3
 * @param concentrationMgM3 实测浓度 mg/m3
 * @param qualified         依据当前门站参数复算的闭区间合格判定
 * @param lowerBoundMgM3    复算区间下限
 * @param upperBoundMgM3    复算区间上限
 */
public record CurvePoint(
        String requestId,
        LocalDateTime detectedAt,
        BigDecimal gasFlowM3,
        BigDecimal concentrationMgM3,
        boolean qualified,
        BigDecimal lowerBoundMgM3,
        BigDecimal upperBoundMgM3) {
}
