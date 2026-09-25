package com.example.starter.dto;

import java.math.BigDecimal;

/**
 * 摘挂响应: 返回新编组版本及重新汇总的辆数/换长/总重。
 */
public record ShuntResponse(long operationId, int consistVersion, int carCount,
                            BigDecimal totalLength, BigDecimal totalWeight) {
}
