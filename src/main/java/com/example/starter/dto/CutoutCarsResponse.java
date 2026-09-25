package com.example.starter.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 关门车清单响应(按编组版本复算)。
 *
 * @param cutoutRatioPercent 关门车比例(%), 恰 6% 合法
 */
public record CutoutCarsResponse(long operationId, int consistVersion, int carCount,
                                 long cutoutCount, BigDecimal cutoutRatioPercent,
                                 List<String> cutoutCarNos) {
}
