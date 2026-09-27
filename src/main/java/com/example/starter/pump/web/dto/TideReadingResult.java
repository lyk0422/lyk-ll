package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 潮位上送结果。
 *
 * @param tideVersion    潮位版本ID（迟到/重复时为既有版本或null）
 * @param zoneCode       流域分区代码
 * @param tideTime       潮位UTC时刻
 * @param tideLevel      潮位（米）
 * @param stored         是否作为新潮位入库（true 新增版本）
 * @param late           是否为迟到较旧潮位（只留证据，不使工况倒退）
 * @param duplicate      是否命中分区+UTC时刻去重
 * @param desiredMode    该潮位下应有工况 NORMAL/STRONG（按当前配置滞后回线判定）
 * @param candidateId    本次生成的候选序列ID（无候选为null）
 * @param candidateDirection 候选方向 ENTER/EXIT（无候选为null）
 * @param replayed       是否同键同参重放
 */
public record TideReadingResult(
        Long tideVersion,
        String zoneCode,
        OffsetDateTime tideTime,
        BigDecimal tideLevel,
        boolean stored,
        boolean late,
        boolean duplicate,
        String desiredMode,
        Long candidateId,
        String candidateDirection,
        boolean replayed
) {
}
