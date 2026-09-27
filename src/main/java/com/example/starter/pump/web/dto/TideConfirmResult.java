package com.example.starter.pump.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 强排切换候选确认结果。
 *
 * @param candidateId 候选序列ID
 * @param zoneCode    流域分区代码
 * @param sequenceId  新启用的启停序列ID
 * @param targetMode  切换后工况 FORCED/NORMAL
 * @param version     切换后的工况版本
 * @param switchedAt  切换生效时刻（Asia/Shanghai）
 * @param taskIds     各站启停任务ID（与候选站目标同序，按站代码排序）
 * @param replayed    是否命中同键重放
 */
public record TideConfirmResult(
        Long candidateId,
        String zoneCode,
        Long sequenceId,
        String targetMode,
        int version,
        LocalDateTime switchedAt,
        List<Long> taskIds,
        boolean replayed
) {
}
