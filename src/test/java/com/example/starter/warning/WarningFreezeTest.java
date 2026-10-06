package com.example.starter.warning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.common.ApiException;
import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitDtos.SubmitReportRequest;
import com.example.starter.permit.PermitService;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import com.example.starter.warning.WarningDtos.LiftResponse;
import com.example.starter.warning.WarningDtos.LiftWarningRequest;
import com.example.starter.warning.WarningDtos.PublishWarningRequest;
import com.example.starter.warning.WarningDtos.WarningResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 大风预警测试：发布与冻结同事务生效，冻结期高风险路段禁止新高空作业许可，解除后恢复。
 */
class WarningFreezeTest extends BaseIntegrationTest {

    @Autowired
    private WarningService warningService;

    @Autowired
    private PermitService permitService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void publishFreezesHighRiskSegmentsAtomically() {
        SegmentResponse highRisk = createSegment("中山路");
        registerLevelOneTree(highRisk.id(), "T-001");
        SegmentResponse lowRisk = createSegment("解放大道");
        registerLevelFourTree(lowRisk.id(), "T-002");

        WarningResponse response = warningService.publish(
                new PublishWarningRequest(nextRequestId("warn"), "GW-2026-001", "RED"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.frozenSegmentIds()).containsExactly(highRisk.id());
        // 同一事务结果：预警与冻结记录同时可见
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gale_warning WHERE warning_no = 'GW-2026-001' AND status = 'ACTIVE'",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM segment_freeze WHERE segment_id = ? AND status = 'FROZEN'",
                Long.class, highRisk.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM segment_freeze WHERE segment_id = ?",
                Long.class, lowRisk.id())).isEqualTo(0);
    }

    @Test
    void frozenSegmentRejectsNewAerialPermitButAllowsGround() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree1 = registerLevelOneTree(segment.id(), "T-001");
        warningService.publish(new PublishWarningRequest(nextRequestId("warn"), "GW-2026-002", "ORANGE"));

        // 高空作业许可被冻结拒绝
        assertThatThrownBy(() -> permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree1.taskId(), "AERIAL")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);

        // 地面作业许可不受冻结影响
        PermitResponse ground = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree1.taskId(), "GROUND"));
        assertThat(ground.status()).isEqualTo("EXECUTING");
        permitService.report(ground.id(),
                new SubmitReportRequest(nextRequestId("report"), "PH-B", "PH-A", "弃枝已清运"));

        // 解除预警后高空作业恢复
        TreeResponse tree2 = registerLevelOneTree(segment.id(), "T-002");
        LiftResponse lift = warningService.lift("GW-2026-002",
                new LiftWarningRequest(nextRequestId("lift")));
        assertThat(lift.status()).isEqualTo("LIFTED");
        assertThat(lift.releasedSegments()).isEqualTo(1);

        PermitResponse aerial = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree2.taskId(), "AERIAL"));
        assertThat(aerial.status()).isEqualTo("EXECUTING");
    }

    @Test
    void duplicateWarningNoRollsBackWholePublish() {
        SegmentResponse segment = createSegment("中山路");
        registerLevelOneTree(segment.id(), "T-001");
        warningService.publish(new PublishWarningRequest(nextRequestId("warn"), "GW-2026-003", "RED"));

        // 同预警编号不同 requestId：业务冲突，整体回滚，不产生重复预警或冻结
        assertThatThrownBy(() -> warningService.publish(
                new PublishWarningRequest(nextRequestId("warn"), "GW-2026-003", "BLUE")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gale_warning", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM segment_freeze", Long.class)).isEqualTo(1);
    }

    @Test
    void warningAndFreezeCommitOrRollbackTogether() {
        SegmentResponse segment = createSegment("中山路");
        registerLevelOneTree(segment.id(), "T-001");

        // 直接验证两张表的事务边界：同事务写入后强制异常，两者均回滚
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LocalDateTime now = LocalDateTime.now(clock);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO gale_warning(warning_no, warning_level, status, published_at)"
                    + " VALUES ('GW-TX-1', 'RED', 'ACTIVE', ?)", now);
            jdbcTemplate.update("INSERT INTO segment_freeze(segment_id, warning_id, frozen_at, status)"
                    + " SELECT ?, id, ?, 'FROZEN' FROM gale_warning WHERE warning_no = 'GW-TX-1'",
                    segment.id(), now);
            throw new IllegalStateException("强制回滚");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gale_warning WHERE warning_no = 'GW-TX-1'", Long.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM segment_freeze WHERE segment_id = ?",
                Long.class, segment.id())).isEqualTo(0);
    }
}
