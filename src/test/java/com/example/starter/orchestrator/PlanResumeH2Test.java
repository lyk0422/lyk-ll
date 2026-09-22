package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.PlanResponse;
import com.example.starter.orchestrator.domain.EventType;
import com.example.starter.orchestrator.repo.EventRow;
import com.example.starter.orchestrator.repo.NodeRow;
import com.example.starter.orchestrator.repo.PlanRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划恢复：模拟同一 JVM 内服务层重新加载未完成计划（直接向 H2 写入中断现场），
 * 验证已确认成功的节点不重复启动、已完成补偿不重复执行。
 */
class PlanResumeH2Test extends AbstractOrchestratorH2Test {

    @Autowired
    private Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void insertRunningPlan(String planId, String requestId) {
        LocalDateTime t = now();
        repo.insertPlan(new PlanRow(null, planId, "v1", requestId, "resume-plan",
                "RUNNING", "hash-" + planId, false, t, t));
    }

    private NodeRow node(String planId, String nodeId, int order, String state,
                         boolean compensated) {
        return new NodeRow(null, planId, nodeId, nodeId, order, 0L,
                false, false, null, "compensate-" + nodeId, false,
                state, compensated, null,
                "SUCCESS".equals(state) || compensated ? "PASSED" : null,
                "PENDING".equals(state) ? null : now().minusSeconds(1),
                null);
    }

    private void event(String planId, String requestId, String nodeId, EventType type) {
        repo.insertEvent(new EventRow(null, planId, "v1", requestId, nodeId,
                type.name(), null, now()));
    }

    private long countEvent(PlanResponse resp, String nodeId, String type) {
        return resp.events().stream()
                .filter(e -> type.equals(e.eventType()) && nodeId.equals(e.nodeId()))
                .count();
    }

    @Test
    void resumeDoesNotRelaunchConfirmedNodesAndContinuesPendingOnes() {
        // 中断现场：db 已确认成功（含历史事件），service 仍 PENDING 依赖 db
        insertRunningPlan("p-resume-1", "req-resume-1");
        repo.insertNode(node("p-resume-1", "db", 0, "SUCCESS", false));
        repo.insertNode(node("p-resume-1", "service", 1, "PENDING", false));
        repo.insertDependency(new com.example.starter.orchestrator.repo.DependencyRow(
                "p-resume-1", "service", "db", "EXPLICIT"));
        event("p-resume-1", "req-resume-1", "db", EventType.NODE_STARTED);
        event("p-resume-1", "req-resume-1", "db", EventType.HEALTH_CHECK_PASSED);

        PlanResponse resp = service.resume("p-resume-1");

        assertThat(resp.status()).isEqualTo("SUCCESS");
        PlanResponse.NodeView db = resp.nodes().stream()
                .filter(n -> "db".equals(n.nodeId())).findFirst().orElseThrow();
        PlanResponse.NodeView svc = resp.nodes().stream()
                .filter(n -> "service".equals(n.nodeId())).findFirst().orElseThrow();
        assertThat(db.state()).isEqualTo("SUCCESS");
        assertThat(svc.state()).isEqualTo("SUCCESS");

        // db 的启动事件仍只有恢复前那一条：没有重复启动
        assertThat(countEvent(resp, "db", "NODE_STARTED")).isEqualTo(1);
        // service 在恢复后启动
        assertThat(countEvent(resp, "service", "NODE_STARTED")).isEqualTo(1);
        // 恢复事件有记录
        assertThat(resp.events()).anyMatch(e -> "PLAN_RESUMED".equals(e.eventType()));
    }

    @Test
    void resumeDoesNotRepeatCompletedCompensationAndBlocksSuccessors() {
        // 中断现场：db 已成功且已补偿；app 启动成功但健康检查失败、已补偿；
        // ui 仍 PENDING 依赖 app，恢复后应被阻断跳过，计划失败
        insertRunningPlan("p-resume-2", "req-resume-2");
        repo.insertNode(node("p-resume-2", "db", 0, "COMPENSATED", true));
        NodeRow app = node("p-resume-2", "app", 1, "FAILED", true);
        repo.insertNode(app.withRuntime("FAILED", true, "health check failed: boom",
                "FAILED", app.startedAt(), now()));
        repo.insertNode(node("p-resume-2", "ui", 2, "PENDING", false));
        repo.insertDependency(new com.example.starter.orchestrator.repo.DependencyRow(
                "p-resume-2", "app", "db", "EXPLICIT"));
        repo.insertDependency(new com.example.starter.orchestrator.repo.DependencyRow(
                "p-resume-2", "ui", "app", "EXPLICIT"));
        event("p-resume-2", "req-resume-2", "db", EventType.COMPENSATE_SUCCEEDED);
        event("p-resume-2", "req-resume-2", "app", EventType.COMPENSATE_SUCCEEDED);

        PlanResponse resp = service.resume("p-resume-2");

        assertThat(resp.status()).isEqualTo("FAILED");
        PlanResponse.NodeView ui = resp.nodes().stream()
                .filter(n -> "ui".equals(n.nodeId())).findFirst().orElseThrow();
        assertThat(ui.state()).isEqualTo("SKIPPED");

        // 已完成的补偿没有重复执行（仍只有恢复前各一条）
        assertThat(countEvent(resp, "db", "COMPENSATE_SUCCEEDED")).isEqualTo(1);
        assertThat(countEvent(resp, "app", "COMPENSATE_SUCCEEDED")).isEqualTo(1);
        assertThat(countEvent(resp, "db", "NODE_STARTED")).isZero();
        assertThat(countEvent(resp, "app", "NODE_STARTED")).isZero();
    }

    @Test
    void resumeOnTerminalPlanAppendsNoEvents() {
        insertRunningPlan("p-resume-3", "req-resume-3");
        repo.insertNode(node("p-resume-3", "a", 0, "SUCCESS", false));
        PlanResponse finished = service.resume("p-resume-3");
        assertThat(finished.status()).isEqualTo("SUCCESS");
        int eventCount = finished.events().size();

        PlanResponse again = service.resume("p-resume-3");
        assertThat(again.status()).isEqualTo("SUCCESS");
        assertThat(again.events()).hasSize(eventCount);
    }

    @Test
    void resumeUnknownPlanIs404() {
        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.resume("p-ghost"));
    }
}
