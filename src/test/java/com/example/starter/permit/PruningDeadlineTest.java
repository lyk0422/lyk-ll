package com.example.starter.permit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitDtos.ReportResponse;
import com.example.starter.permit.PermitDtos.SubmitReportRequest;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.tree.TreeDtos.TreeResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 一级风险 7 日闭环规则测试：恰第 7 日闭环有效，逾期升级督办。
 */
class PruningDeadlineTest extends BaseIntegrationTest {

    @Autowired
    private PermitService permitService;

    @Autowired
    private PruningTaskRepository taskRepository;

    @Test
    void levelOneTreeGetsSevenDayTask() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");

        assertThat(tree.riskLevel()).isEqualTo(1);
        assertThat(tree.taskId()).isNotNull();
        // 创建日 2026-01-05，截止日恰为第 7 日 2026-01-12
        assertThat(tree.taskDeadline()).isEqualTo(LocalDate.of(2026, 1, 12));
    }

    @Test
    void closureOnExactDaySevenIsOnTime() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");
        PermitResponse permit = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree.taskId(), "AERIAL"));

        // 推进到恰第 7 日（截止日当天）
        clock.setDate(LocalDate.of(2026, 1, 12));
        ReportResponse report = permitService.report(permit.id(),
                new SubmitReportRequest(nextRequestId("report"), "PH-B-1", "PH-A-1", "弃枝 3 车已清运"));

        assertThat(report.onTime()).isTrue();
        assertThat(report.escalated()).isFalse();
        var task = taskRepository.findById(tree.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo("CLOSED");
        assertThat(task.escalated()).isFalse();
    }

    @Test
    void closureOnDayEightIsEscalated() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");
        PermitResponse permit = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree.taskId(), "GROUND"));

        // 推进到第 8 日，超过截止日
        clock.setDate(LocalDate.of(2026, 1, 13));
        ReportResponse report = permitService.report(permit.id(),
                new SubmitReportRequest(nextRequestId("report"), "PH-B-1", "PH-A-1", "弃枝 2 车已清运"));

        assertThat(report.onTime()).isFalse();
        assertThat(report.escalated()).isTrue();
        var task = taskRepository.findById(tree.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo("CLOSED");
        assertThat(task.escalated()).isTrue();
    }

    @Test
    void reportRequiresPhotosAndDisposal() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");
        PermitResponse permit = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree.taskId(), "AERIAL"));

        // 服务层同样拒绝空白字段（Web 层由 Bean Validation 拦截，见 WebApiTest）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> permitService.report(permit.id(),
                new SubmitReportRequest(nextRequestId("report"), " ", "PH-A-1", "弃枝已清运")))
                .isInstanceOf(com.example.starter.common.ApiException.class)
                .hasMessageContaining("不能为空");
    }
}
