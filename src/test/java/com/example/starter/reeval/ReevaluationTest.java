package com.example.starter.reeval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitService;
import com.example.starter.reeval.ReevalDtos.MetricItem;
import com.example.starter.reeval.ReevalDtos.ReevaluateRequest;
import com.example.starter.reeval.ReevalDtos.ReevaluateResponse;
import com.example.starter.segment.SegmentDtos.SegmentResponse;
import com.example.starter.support.BaseIntegrationTest;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.tree.TreeDtos.RegisterTreeRequest;
import com.example.starter.tree.TreeDtos.TreeResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 年度复评测试：评级变化触发修剪计划重排，重排不改变在途许可既定时限。
 */
class ReevaluationTest extends BaseIntegrationTest {

    @Autowired
    private ReevaluationService reevaluationService;

    @Autowired
    private PermitService permitService;

    @Autowired
    private PruningTaskRepository taskRepository;

    @Test
    void levelUpgradeReplansPendingTaskToSevenDays() {
        SegmentResponse segment = createSegment("中山路");
        // 三级树（score 34），原任务 90 天期限
        TreeResponse tree = treeService.register(new RegisterTreeRequest(nextRequestId("tree"),
                segment.id(), "T-001", "香樟", 40, 60, 10.0, false));
        assertThat(tree.riskLevel()).isEqualTo(3);
        assertThat(tree.taskDeadline()).isEqualTo(DAY_ZERO.plusDays(90));

        // 一年后复评恶化为一级（score 72）
        clock.plusDays(365);
        ReevaluateResponse response = reevaluationService.reevaluate(new ReevaluateRequest(
                nextRequestId("reeval"),
                List.of(new MetricItem("T-001", 35, 18.0, 60, true))));

        var item = response.results().get(0);
        assertThat(item.oldLevel()).isEqualTo(3);
        assertThat(item.newLevel()).isEqualTo(1);
        assertThat(item.action()).isEqualTo("REPLANNED");
        LocalDate expectedDeadline = LocalDate.of(2027, 1, 5).plusDays(7);
        assertThat(item.taskDeadline()).isEqualTo(expectedDeadline);

        var task = taskRepository.findById(tree.taskId()).orElseThrow();
        assertThat(task.deadlineDate()).isEqualTo(expectedDeadline);
        assertThat(task.planVersion()).isEqualTo(2);
        assertThat(task.riskLevel()).isEqualTo(1);
    }

    @Test
    void inFlightPermitDeadlineIsNotChangedByReplan() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");
        PermitResponse permit = permitService.issue(
                new IssuePermitRequest(nextRequestId("permit"), tree.taskId(), "AERIAL"));
        LocalDate originalDeadline = permit.deadlineDate();

        // 复评降级为二级：在途许可既定时限不变
        ReevaluateResponse response = reevaluationService.reevaluate(new ReevaluateRequest(
                nextRequestId("reeval"),
                List.of(new MetricItem("T-001", 45, 12.0, 50, true))));

        var item = response.results().get(0);
        assertThat(item.newLevel()).isEqualTo(2);
        assertThat(item.action()).isEqualTo("KEPT_IN_FLIGHT");
        assertThat(item.taskDeadline()).isEqualTo(originalDeadline);

        var permitRow = jdbcTemplate.queryForObject(
                "SELECT deadline_date FROM work_permit WHERE id = ?", LocalDate.class, permit.id());
        assertThat(permitRow).isEqualTo(originalDeadline);
        var task = taskRepository.findById(tree.taskId()).orElseThrow();
        assertThat(task.deadlineDate()).isEqualTo(originalDeadline);
    }

    @Test
    void levelDropToFourCancelsPendingTask() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");

        ReevaluateResponse response = reevaluationService.reevaluate(new ReevaluateRequest(
                nextRequestId("reeval"),
                List.of(new MetricItem("T-001", 90, 2.0, 10, false))));

        var item = response.results().get(0);
        assertThat(item.newLevel()).isEqualTo(4);
        assertThat(item.action()).isEqualTo("CANCELLED");
        assertThat(taskRepository.findById(tree.taskId()).orElseThrow().status()).isEqualTo("CANCELLED");
    }

    @Test
    void levelRiseToOneCreatesNewSevenDayTask() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelFourTree(segment.id(), "T-001");
        assertThat(tree.taskId()).isNull();

        ReevaluateResponse response = reevaluationService.reevaluate(new ReevaluateRequest(
                nextRequestId("reeval"),
                List.of(new MetricItem("T-001", 35, 18.0, 60, true))));

        var item = response.results().get(0);
        assertThat(item.oldLevel()).isEqualTo(4);
        assertThat(item.newLevel()).isEqualTo(1);
        assertThat(item.action()).isEqualTo("CREATED");
        assertThat(item.taskDeadline()).isEqualTo(DAY_ZERO.plusDays(7));
    }

    @Test
    void unchangedLevelKeepsPlanUntouched() {
        SegmentResponse segment = createSegment("中山路");
        TreeResponse tree = registerLevelOneTree(segment.id(), "T-001");

        ReevaluateResponse response = reevaluationService.reevaluate(new ReevaluateRequest(
                nextRequestId("reeval"),
                List.of(new MetricItem("T-001", 35, 18.0, 61, true))));

        var item = response.results().get(0);
        assertThat(item.action()).isEqualTo("NONE");
        var task = taskRepository.findById(tree.taskId()).orElseThrow();
        assertThat(task.planVersion()).isEqualTo(1);
        assertThat(task.deadlineDate()).isEqualTo(DAY_ZERO.plusDays(7));
    }
}
