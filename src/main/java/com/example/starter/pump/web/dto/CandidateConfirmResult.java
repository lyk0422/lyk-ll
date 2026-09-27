package com.example.starter.pump.web.dto;

import java.util.List;

/**
 * 切换候选确认结果：原子启用新序列、终结旧序列后的落账信息。
 *
 * @param candidateId 切换候选ID
 * @param zoneCode    流域分区代码
 * @param direction   切换方向 ENTER_FORCED/EXIT_FORCED
 * @param sequenceId  新启用的启停序列ID
 * @param taskIds     本次写入的各站任务ID（按站点代码排序）
 * @param newVersion  切换后的分区工况版本
 * @param mode        切换后的工况 NORMAL/FORCED
 * @param replayed    是否为同键重放返回
 */
public record CandidateConfirmResult(
        Long candidateId,
        String zoneCode,
        String direction,
        Long sequenceId,
        List<Long> taskIds,
        int newVersion,
        String mode,
        boolean replayed
) {
}
