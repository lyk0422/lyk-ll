package com.example.starter.ledger;

import java.util.List;
import java.util.Map;

/**
 * 台账接口出入参。
 */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    /**
     * 路段台账行。
     * riskDistribution：风险等级（"1"-"4"，"UNEVALUATED" 未评估）到树木数的映射；
     * closureRate：闭环率 = 已闭环任务 / 任务总数，无任务时为 null；保留 4 位小数。
     */
    public record SegmentLedger(long segmentId, String segmentName, Map<String, Long> riskDistribution,
            long totalTasks, long openTasks, long closedTasks, long onTimeClosedTasks,
            long escalatedTasks, Double closureRate) {
    }

    /** 台账响应：按路段 id 升序，重复查询稳定。 */
    public record LedgerResponse(List<SegmentLedger> segments) {
    }
}
