package com.example.starter.pump.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 液位复测数据上送。按“站点＋采集时刻”去重，不使用 requestId。
 *
 * @param stationCode 泵站代码
 * @param readingTime 液位采集时刻（Asia/Shanghai）
 * @param level       河道水位（米）
 */
public record LevelIngestRequest(
        @NotBlank String stationCode,
        @NotNull LocalDateTime readingTime,
        @NotNull BigDecimal level
) {
}
