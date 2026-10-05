package com.example.starter.flood.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 滚动调洪演算版本视图：含版本号、演算基准时刻、泄流能力判定与自动处置结果。
 */
public record RollingVersionView(
        String rollingNo,
        String warningNo,
        Integer versionNo,
        LocalDateTime scheduledTime,
        BigDecimal currentWaterLevel,
        BigDecimal currentStorage,
        BigDecimal maxStorage,
        BigDecimal availableCapacity,
        BigDecimal inflowRate,
        BigDecimal periodInflowVolume,
        BigDecimal releaseCapacity,
        BigDecimal requiredRelease,
        BigDecimal projectedStorage,
        String releaseJudgment,
        BigDecimal dryBeachLength,
        BigDecimal phreaticDepth,
        String dryBeachJudgment,
        String phreaticJudgment,
        String dangerJudgment,
        String warningLevelAfter,
        Integer planVersionAfter,
        String inputSnapshot,
        String criterion,
        boolean replay,
        LocalDateTime createdAt) {
}
