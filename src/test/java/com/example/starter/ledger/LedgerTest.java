package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ledger.LedgerDtos.LedgerResponse;
import com.example.starter.ledger.LedgerDtos.SegmentLedger;
import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitDtos.SubmitReportRequest;
import com.example.starter.permit.PermitService;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 台账测试：风险分布与闭环率可复算，重复查询稳定，逾期任务幂等升级督办。
 */
class LedgerTest extends BaseIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PermitService permitService;

    @Test
    void ledgerAggregatesRiskDistributionAndClosureRate() {
        SegmentResponse seg1 = createSegment("中山路");
        // 一级树：闭环一张任务
        TreeResponse t1 = registerLevelOneTree(seg1.id(), "T-001");
        // 二级树：任务待办（score = 27.5+12+5+15 = 60）
        treeService.register(new RegisterTreeRequest(nextRequestId("tree"), seg1.id(), "T-002",
                "银杏", 50, 45, 12.0, true));
        SegmentResponse seg2 = createSegment("解放大道");
        registerLevelFourTree(seg2.id(), "T-003");

        PermitResponse permit = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), t1.taskId(), "GROUND"));
        permitService.report(permit.id(),
                new SubmitReportRequest(nextRequestId("report"), "PH-B", "PH-A", "弃枝已清运"));

        LedgerResponse ledger = ledgerService.ledger();
        assertThat(ledger.segments()).hasSize(2);

        SegmentLedger s1 = ledger.segments().get(0);
        assertThat(s1.segmentId()).isEqualTo(seg1.id());
        assertThat(s1.riskDistribution()).containsEntry("1", 1L).containsEntry("2", 1L);
        assertThat(s1.totalTasks()).isEqualTo(2);
        assertThat(s1.closedTasks()).isEqualTo(1);
        assertThat(s1.onTimeClosedTasks()).isEqualTo(1);
        assertThat(s1.openTasks()).isEqualTo(1);
        assertThat(s1.closureRate()).isEqualTo(0.5);

        SegmentLedger s2 = ledger.segments().get(1);
        assertThat(s2.riskDistribution()).containsEntry("4", 1L);
        assertThat(s2.totalTasks()).isZero();
        assertThat(s2.closureRate()).isNull();
    }

    @Test
    void repeatedQueriesAreStable() {
        SegmentResponse segment = createSegment("中山路");
        registerLevelOneTree(segment.id(), "T-001");

        LedgerResponse first = ledgerService.ledger();
        LedgerResponse second = ledgerService.ledger();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void overdueOpenTaskIsEscalatedIdempotently() {
        SegmentResponse segment = createSegment("中山路");
        registerLevelOneTree(segment.id(), "T-001");

        // 推进到第 8 日，任务逾期未闭环
        clock.setDate(LocalDate.of(2026, 1, 13));
        LedgerResponse first = ledgerService.ledger();
        SegmentLedger s1 = first.segments().get(0);
        assertThat(s1.escalatedTasks()).isEqualTo(1);
        assertThat(s1.openTasks()).isEqualTo(1);

        // 重复查询：督办标记幂等，结果稳定
        LedgerResponse second = ledgerService.ledger();
        assertThat(second).isEqualTo(first);
        Long escalated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pruning_task WHERE escalated = TRUE", Long.class);
        assertThat(escalated).isEqualTo(1);
    }
}
