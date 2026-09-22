package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.SubmitPlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.starter.orchestrator.PlanTestFactory.node;
import static com.example.starter.orchestrator.PlanTestFactory.nodeSuccessCondition;
import static com.example.starter.orchestrator.PlanTestFactory.paramEqualsCondition;
import static com.example.starter.orchestrator.PlanTestFactory.plan;
import static com.example.starter.orchestrator.PlanTestFactory.withCondition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 失败分支：提交结构校验、显式/条件引用循环检测（执行前）。
 */
class PlanValidationH2Test extends AbstractOrchestratorH2Test {

    @Test
    void explicitCycleRejectedBeforeExecutionWithFullPath() {
        SubmitPlanRequest req = plan("p-cycle", "v1", "req-cycle", List.of(
                node("a", "b"),
                node("b", "c"),
                node("c", "a")));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("cyclic dependency detected")
                .hasMessageContaining("a -> b -> c -> a");

        // 计划未入库、未执行
        assertThat(repo.findPlanByPlanId("p-cycle")).isNull();
        assertThat(repo.findEventsByPlanId("p-cycle")).isEmpty();
    }

    @Test
    void cycleIntroducedByConditionReferenceIsDetected() {
        // 显式依赖：x 无；y -> x；条件：x 引用 y（NODE_SUCCESS）-> 隐式环 x -> y
        SubmitPlanRequest req = plan("p-cond-cycle", "v1", "req-cond-cycle", List.of(
                withCondition("x", nodeSuccessCondition("y")),
                node("y", "x")));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("cyclic dependency detected")
                .hasMessageContaining("->");
    }

    @Test
    void unknownExplicitDependencyReferenceRejected() {
        SubmitPlanRequest req = plan("p-bad-ref", "v1", "req-bad-ref", List.of(
                node("a", "ghost")));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("unknown node");
    }

    @Test
    void unknownConditionReferenceRejected() {
        SubmitPlanRequest req = plan("p-bad-cond-ref", "v1", "req-bad-cond-ref", List.of(
                node("a"),
                withCondition("b", nodeSuccessCondition("ghost"))));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("condition references unknown node");
    }

    @Test
    void duplicateNodeIdRejected() {
        SubmitPlanRequest req = plan("p-dup", "v1", "req-dup", List.of(
                node("a"),
                node("a")));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("duplicate nodeId");
    }

    @Test
    void paramEqualsRequiresParameterName() {
        SubmitPlanRequest req = plan("p-bad-predicate", "v1", "req-bad-predicate", List.of(
                node("a"),
                withCondition("b", paramEqualsCondition("a", null, "v"))));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("PARAM_EQUALS");
    }

    @Test
    void selfDependencyReportedAsCycle() {
        SubmitPlanRequest req = plan("p-self", "v1", "req-self", List.of(
                node("a", "a")));

        assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(CyclicDependencyException.class)
                .hasMessageContaining("a -> a");
    }

    @Test
    void conditionReferencingSameNodeAsExplicitDepIsNotDuplicated() {
        // 同一引用既是显式依赖又是条件引用：合法（去重），node_dependency 只登记一条 EXPLICIT
        SubmitPlanRequest req = plan("p-both", "v1", "req-both", List.of(
                node("db"),
                withCondition("app", nodeSuccessCondition("db"), "db")));

        var resp = service.submit(req);
        assertThat(resp.status()).isEqualTo("SUCCESS");
        long explicitCount = repo.findDependenciesByPlanId("p-both").stream()
                .filter(d -> "EXPLICIT".equals(d.depKind())).count();
        long conditionCount = repo.findDependenciesByPlanId("p-both").stream()
                .filter(d -> "CONDITION".equals(d.depKind())).count();
        assertThat(explicitCount).isEqualTo(1);
        assertThat(conditionCount).isZero();
    }
}
