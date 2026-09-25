package com.example.starter.reinsurance.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 估损调整请求。赔案结案后的迟到调整只记事件，不改动摊回与账户。
 *
 * @param newEstimatedAmount 新估损金额（元）
 */
public record AdjustEstimateRequest(@NotNull BigDecimal newEstimatedAmount) {
}
