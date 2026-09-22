package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.PlanResponse;
import com.example.starter.orchestrator.api.SubmitPlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.example.starter.orchestrator.PlanTestFactory.eventTypes;
import static com.example.starter.orchestrator.PlanTestFactory.healthFailing;
import static com.example.starter.orchestrator.PlanTestFactory.node;
import static com.example.starter.orchestrator.PlanTestFactory.nodeSuccessCondition;
import static com.example.starter.orchestrator.PlanTestFactory.nodeWithParams;
import static com.example.starter.orchestrator.PlanTestFactory.paramEqualsCondition;
import static com.example.starter.orchestrator.PlanTestFactory.plan;
import static com.example.starter.orchestrator.PlanTestFactory.startFailing;
import static com.example.starter.orchestrator.PlanTestFactory.withCompensation;
import static com.example.starter.orchestrator.PlanTestFactory.withCondition;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主流程：DAG 顺序启动、条件谓词、失败传播阻断、健康检查失败与逆依赖补偿。
 */
class PlanExecutionH2Test extends AbstractOrchestratorH2Test {

    private PlanResponse.NodeView nodeView(PlanResponse resp, String nodeId) {
        return resp.nodes().stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    void successfulPlanLaunchesNodesInDependencyOrder() {
        SubmitPlanRequest req = plan("p-success", "v1", "req-success", List.of(
                node("ui", "service"),
                node("service", "db"),
                node("db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("SUCCESS");
        // 节点视图保持提交声明顺序；DAG 启动顺序由下方事件序列验证
        assertThat(resp.nodes()).extracting(PlanResponse.NodeView::nodeId)
                .containsExactly("ui", "service", "db");
        assertThat(resp.nodes()).allSatisfy(n -> {
            assertThat(n.state()).isEqualTo("SUCCESS");
            // 未声明健康检查时启动成功即视为健康（PASSED）
            assertThat(n.healthStatus()).isEqualTo("PASSED");
            assertThat(n.compensated()).isFalse();
        });

        List<String> events = eventTypes(resp);
        assertThat(events).containsSubsequence(
                "PLAN_SUBMITTED", "PLAN_STARTED",
                "NODE_LAUNCHING", "NODE_STARTED",
                "PLAN_SUCCEEDED");
        // 启动顺序：db 先于 service 先于 ui
        assertThat(indexOfStarted(resp, "db"))
                .isLessThan(indexOfStarted(resp, "service"));
        assertThat(indexOfStarted(resp, "service"))
                .isLessThan(indexOfStarted(resp, "ui"));
        assertThat(events).doesNotContain("COMPENSATE_STARTED", "NODE_SKIPPED");
    }

    private int indexOfStarted(PlanResponse resp, String nodeId) {
        List<String> events = eventTypes(resp);
        for (int i = 0; i < resp.events().size(); i++) {
            PlanResponse.EventView e = resp.events().get(i);
            if ("NODE_STARTED".equals(e.eventType()) && nodeId.equals(e.nodeId())) {
                return i;
            }
        }
        throw new AssertionError("no NODE_STARTED event for " + nodeId);
    }

    @Test
    void nodeSuccessConditionSatisfiedAllowsStart() {
        SubmitPlanRequest req = plan("p-cond-ok", "v1", "req-cond-ok", List.of(
                node("db"),
                withCondition("monitor", nodeSuccessCondition("db"), "db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(nodeView(resp, "db").state()).isEqualTo("SUCCESS");
        assertThat(nodeView(resp, "monitor").state()).isEqualTo("SUCCESS");
    }

    @Test
    void paramEqualsConditionUnmetSkipsDependentButPlanSucceeds() {
        SubmitPlanRequest req = plan("p-cond-skip", "v1", "req-cond-skip", List.of(
                nodeWithParams("config", Map.of("mode", "lite")),
                withCondition("heavy-worker",
                        paramEqualsCondition("config", "mode", "full"), "config")));

        PlanResponse resp = service.submit(req);

        // 条件不满足：heavy-worker 不进入已启动而被跳过；其他节点成功即计划成功
        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(nodeView(resp, "config").state()).isEqualTo("SUCCESS");
        assertThat(nodeView(resp, "heavy-worker").state()).isEqualTo("SKIPPED");
        assertThat(nodeView(resp, "heavy-worker").failureReason()).contains("PARAM_EQUALS");
        assertThat(eventTypes(resp)).contains("NODE_SKIPPED");
    }

    @Test
    void failedDependencyBlocksSuccessorsAndPlanFails() {
        SubmitPlanRequest req = plan("p-fail-propagate", "v1", "req-fail-propagate", List.of(
                node("ui", "service"),
                node("service", "db"),
                startFailing("db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "db").state()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "service").state()).isEqualTo("SKIPPED");
        assertThat(nodeView(resp, "ui").state()).isEqualTo("SKIPPED");
        assertThat(nodeView(resp, "service").failureReason()).contains("dependency failed");
        assertThat(eventTypes(resp)).endsWith("PLAN_FAILED");
    }

    @Test
    void healthCheckFailureTriggersReverseDependencyCompensation() {
        // 依赖：app->db；app 健康检查失败。db 先启动成功，app 启动成功但健康检查失败。
        // 已启动节点集合 {db, app}，逆依赖顺序：app 先补偿，db 后补偿。
        SubmitPlanRequest req = plan("p-health-fail", "v1", "req-health-fail", List.of(
                withCompensation("db", "rollback-db-schema", false),
                healthFailing("app", "stop-app", "db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        // app 启动成功但健康检查失败：补偿执行后仍标记 FAILED 且 compensated=1
        assertThat(nodeView(resp, "app").state()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "app").healthStatus()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "app").compensated()).isTrue();
        // db 健康成功但被逆依赖回滚
        assertThat(nodeView(resp, "db").state()).isEqualTo("COMPENSATED");
        assertThat(nodeView(resp, "db").compensated()).isTrue();

        List<String> compNodes = resp.events().stream()
                .filter(e -> "COMPENSATE_SUCCEEDED".equals(e.eventType()))
                .map(PlanResponse.EventView::nodeId).toList();
        // 逆依赖顺序：app（依赖方）先于 db（被依赖方）
        assertThat(compNodes).containsExactly("app", "db");
    }

    @Test
    void downstreamNodesDoNotLaunchWhenIntermediateFails() {
        SubmitPlanRequest req = plan("p-chain-fail", "v1", "req-chain-fail", List.of(
                node("a"),
                startFailing("b", "a"),
                node("c", "b")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "a").state()).isEqualTo("SUCCESS");
        assertThat(nodeView(resp, "b").state()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "c").state()).isEqualTo("SKIPPED");
    }

    @Test
    void compensationOrderIsReverseDependencyWithSharedDependencyLast() {
        // db 为共享前置；app 与 cache 同波启动，app 健康检查失败。
        // 已启动集合 {db, app, cache}，逆依赖补偿：app/cache 先，db 最后。
        SubmitPlanRequest req = plan("p-comp-order", "v1", "req-comp-order", List.of(
                withCompensation("db", "rollback-db", false),
                healthFailing("app", "stop-app", "db"),
                withCompensation("cache", "flush-cache", false, "db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        List<String> compNodes = resp.events().stream()
                .filter(e -> "COMPENSATE_SUCCEEDED".equals(e.eventType()))
                .map(PlanResponse.EventView::nodeId).toList();
        assertThat(compNodes).hasSize(3);
        assertThat(compNodes.get(2)).isEqualTo("db");
        assertThat(compNodes.subList(0, 2)).containsExactlyInAnyOrder("app", "cache");
        assertThat(nodeView(resp, "db").state()).isEqualTo("COMPENSATED");
        assertThat(nodeView(resp, "cache").state()).isEqualTo("COMPENSATED");
        assertThat(nodeView(resp, "app").state()).isEqualTo("FAILED");
    }

    @Test
    void nodeStartFailureRollsBackPreviouslyStartedNodes() {
        // db 启动成功（声明补偿）；app 启动即失败（未真正启动，不补偿自身）；db 被逆依赖回滚
        SubmitPlanRequest req = plan("p-start-fail", "v1", "req-start-fail", List.of(
                withCompensation("db", "rollback-db", false),
                startFailing("app", "db")));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "db").state()).isEqualTo("COMPENSATED");
        assertThat(nodeView(resp, "db").compensated()).isTrue();
        assertThat(nodeView(resp, "app").state()).isEqualTo("FAILED");
        assertThat(nodeView(resp, "app").compensated()).isFalse();
        assertThat(resp.events()).noneMatch(e -> "COMPENSATE_STARTED".equals(e.eventType())
                && "app".equals(e.nodeId()));
    }

    @Test
    void launchDelayIsRecordedOnLaunchingEvent() {
        SubmitPlanRequest req = plan("p-delay", "v1", "req-delay", List.of(
                PlanTestFactory.delayed("slow", 60)));

        long begin = System.currentTimeMillis();
        PlanResponse resp = service.submit(req);
        long elapsed = System.currentTimeMillis() - begin;

        assertThat(resp.status()).isEqualTo("SUCCESS");
        assertThat(elapsed).isGreaterThanOrEqualTo(50);
        assertThat(resp.events()).anyMatch(e -> "NODE_LAUNCHING".equals(e.eventType())
                && "slow".equals(e.nodeId())
                && "launchDelayMs=60".equals(e.detail()));
    }

    @Test
    void failedCompensationRunsOnceAndIsNotRetriedOnReload() {
        // app 健康检查失败且其补偿动作本身失败：补偿只执行一次，重新加载不重试
        SubmitPlanRequest req = plan("p-comp-fail", "v1", "req-comp-fail", List.of(
                new SubmitPlanRequest.NodeSpec("app", "app", 0L, false, Map.of(),
                        List.of(), null,
                        new com.example.starter.orchestrator.api.SubmitPlanRequest.HealthCheckSpec(
                                true, "unhealthy"),
                        new com.example.starter.orchestrator.api.SubmitPlanRequest.CompensationSpec(
                                "broken-rollback", true))));

        PlanResponse resp = service.submit(req);

        assertThat(resp.status()).isEqualTo("FAILED");
        PlanResponse.NodeView app = nodeView(resp, "app");
        assertThat(app.state()).isEqualTo("FAILED");
        assertThat(app.compensated()).isTrue();
        assertThat(app.failureReason()).contains("compensation failed");
        long failedEvents = resp.events().stream()
                .filter(e -> "COMPENSATE_FAILED".equals(e.eventType())
                        && "app".equals(e.nodeId())).count();
        long succeededEvents = resp.events().stream()
                .filter(e -> "COMPENSATE_SUCCEEDED".equals(e.eventType())).count();
        assertThat(failedEvents).isEqualTo(1);
        assertThat(succeededEvents).isZero();

        // 终态计划重新加载：补偿不重试，事件不增加
        int eventCount = resp.events().size();
        PlanResponse reloaded = service.resume("p-comp-fail");
        assertThat(reloaded.events()).hasSize(eventCount);
        long failedEventsAfter = reloaded.events().stream()
                .filter(e -> "COMPENSATE_FAILED".equals(e.eventType())
                        && "app".equals(e.nodeId())).count();
        assertThat(failedEventsAfter).isEqualTo(1);
    }
}
