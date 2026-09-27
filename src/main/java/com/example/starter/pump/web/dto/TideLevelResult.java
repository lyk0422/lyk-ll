package com.example.starter.pump.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 潮位上送处理结果。
 *
 * @param readingId      潮位数据ID；去重命中时返回首次ID，迟到证据为 null
 * @param zoneCode       流域分区代码
 * @param observedAt     潮位观测时刻（UTC）
 * @param tideLevel      潮位（米）
 * @param accepted       是否为新受理（false 表示去重或迟到）
 * @param deduplicated   是否命中分区＋UTC时刻去重
 * @param late           是否为迟到/归档后证据（只留证据，不让工况倒退）
 * @param decision       工况判定 NONE无变化/ENTER_FORCED进入强排/EXIT_FORCED退出强排
 * @param candidateId    本次生成或复用的候选序列ID，无则 null
 * @param currentVersion 处理后的当前工况版本
 * @param replayed       是否命中同键重放
 */
public record TideLevelResult(
        Long readingId,
        String zoneCode,
        Instant observedAt,
        BigDecimal tideLevel,
        boolean accepted,
        boolean deduplicated,
        boolean late,
        String decision,
        Long candidateId,
        int currentVersion,
        boolean replayed
) {
}
