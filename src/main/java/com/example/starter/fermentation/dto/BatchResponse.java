package com.example.starter.fermentation.dto;

import com.example.starter.fermentation.BatchStatus;

import java.time.Instant;

/**
 * 批次视图：含当前状态与生效曲线版本，供复算与接口返回使用。
 */
public record BatchResponse(
        Long id,
        String batchNo,
        String tankCode,
        BatchStatus status,
        Instant filledAt,
        Integer activeCurveVersion) {
}
