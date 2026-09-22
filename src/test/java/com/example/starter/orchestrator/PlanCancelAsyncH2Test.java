package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.CancelPlanRequest;
import com.example.starter.orchestrator.api.PlanResponse;
import com.example.starter.orchestrator.api.SubmitPlanRequest;
import com.example.starter.orchestrator.repo.OrchestratorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import static com.example.starter.orchestrator.PlanTestFactory.delayed;
import static com.example.starter.orchestrator.PlanTestFactory.plan;
import static com.example.starter.orchestrator.PlanTestFactory.withCompensation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消测试：使用生产异步执行器，计划含启动延迟以制造取消窗口；
 * 轮询断言带超时，不靠长时间 sleep 充当验证。
 */
@SpringBootTest(properties = "orchestrator.plan-executor.async=true")
class PlanCancelAsyncH2Test {

    @Autowired
    private com.example.starter.orchestrator.service.LaunchPlanService service;

    @Autowired
    private OrchestratorRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAllData();
    }

    private PlanResponse.NodeView node(PlanResponse resp, String id) {
        return resp.nodes().stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private PlanResponse awaitState(String planId, Duration timeout, Predicate<PlanResponse> done) {
        Instant deadline = Instant.now().plus(timeout);
        PlanResponse resp;
        while (Instant.now().isBefore(deadline)) {
            resp = service.getPlan(planId);
            if (done.test(resp)) {
                return resp;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return service.getPlan(planId);
    }

    private PlanResponse awaitStatus(String planId, String status) {
        return awaitState(planId, Duration.ofSeconds(10), r -> status.equals(r.status()));
    }

    @Test
    void cancelBeforeAnyNodeStartsMarksPendingNodesCancelled() {
        // 所有节点都在启动延迟窗口内被取消：均不进入已启动状态
        SubmitPlanRequest req = plan("p-cancel-early", "v1", "req-cancel-early", List.of(
                delayed("a", 1000),
                delayed("b", 1000, "a")));
        service.submit(req);

        service.cancel("p-cancel-early", new CancelPlanRequest("cancel-1"));

        PlanResponse resp = awaitStatus("p-cancel-early", "CANCELLED");

        assertThat(resp.status()).isEqualTo("CANCELLED");
        assertThat(node(resp, "a").state()).isEqualTo("CANCELLED");
        assertThat(node(resp, "b").state()).isEqualTo("CANCELLED");
        // 没有任何节点真正启动，无补偿事件
        assertThat(resp.events()).noneMatch(e -> "NODE_STARTED".equals(e.eventType()));
        assertThat(resp.events()).noneMatch(e -> "COMPENSATE_STARTED".equals(e.eventType()));
        assertThat(resp.events()).anyMatch(e -> "CANCEL_REQUESTED".equals(e.eventType()));
    }

    @Test
    void cancelAfterStartCancelsStartedNodeWithCompensationExactlyOnce() {
        // a 很快启动成功（声明补偿），b 有长延迟。取消时：a 已启动 -> 取消并补偿一次；b 未启动 -> 取消
        SubmitPlanRequest req = plan("p-cancel-mid", "v1", "req-cancel-mid", List.of(
                withCompensation("a", "teardown-a", false),
                delayed("b", 2000, "a")));
        service.submit(req);

        // 等待 a 确认成功后再取消
        awaitState("p-cancel-mid", Duration.ofSeconds(5), r -> r.nodes().stream()
                .anyMatch(n -> "a".equals(n.nodeId()) && "SUCCESS".equals(n.state())));

        // 取消幂等：连续调用两次，结果一致
        service.cancel("p-cancel-mid", new CancelPlanRequest("cancel-2a"));
        service.cancel("p-cancel-mid", new CancelPlanRequest("cancel-2b"));

        PlanResponse resp = awaitStatus("p-cancel-mid", "CANCELLED");

        assertThat(resp.status()).isEqualTo("CANCELLED");
        assertThat(node(resp, "a").state()).isEqualTo("CANCELLED");
        assertThat(node(resp, "a").compensated()).isTrue();
        assertThat(node(resp, "b").state()).isEqualTo("CANCELLED");

        // 补偿只执行一次
        long compStarted = resp.events().stream()
                .filter(e -> "COMPENSATE_STARTED".equals(e.eventType())
                        && "a".equals(e.nodeId())).count();
        long compSucceeded = resp.events().stream()
                .filter(e -> "COMPENSATE_SUCCEEDED".equals(e.eventType())
                        && "a".equals(e.nodeId())).count();
        assertThat(compStarted).isEqualTo(1);
        assertThat(compSucceeded).isEqualTo(1);
        // 取消请求事件只有第一条生效（幂等）
        long cancelRequests = resp.events().stream()
                .filter(e -> "CANCEL_REQUESTED".equals(e.eventType())).count();
        assertThat(cancelRequests).isEqualTo(1);
    }

    @Test
    void cancelOnUnknownPlanIs404AndIdempotentOnTerminalPlan() {
        org.junit.jupiter.api.Assertions.assertThrows(NotFoundException.class,
                () -> service.cancel("p-missing", new CancelPlanRequest("c")));

        SubmitPlanRequest req = plan("p-done", "v1", "req-done", List.of(delayed("a", 0)));
        service.submit(req);
        awaitStatus("p-done", "SUCCESS");

        // 终态计划上取消：不改变状态、不新增补偿
        PlanResponse before = service.getPlan("p-done");
        service.cancel("p-done", new CancelPlanRequest("c-late"));
        PlanResponse after = service.getPlan("p-done");
        assertThat(after.status()).isEqualTo("SUCCESS");
        assertThat(after.events()).hasSameSizeAs(before.events());
    }
}
