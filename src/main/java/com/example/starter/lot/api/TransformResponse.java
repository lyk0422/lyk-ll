package com.example.starter.lot.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 转换结果：首次成功的完整摘要，同键同参请求直接重放。
 */
public record TransformResponse(
        String requestId,
        boolean replayed,
        String unit,
        BigDecimal totalConsumed,
        BigDecimal totalProduced,
        BigDecimal loss,
        List<String> parentLotIds,
        List<String> childLotIds
) {
}
