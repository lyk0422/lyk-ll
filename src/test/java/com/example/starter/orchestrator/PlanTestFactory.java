package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.SubmitPlanRequest;
import com.example.starter.orchestrator.api.SubmitPlanRequest.ConditionSpec;
import com.example.starter.orchestrator.api.SubmitPlanRequest.CompensationSpec;
import com.example.starter.orchestrator.api.SubmitPlanRequest.HealthCheckSpec;
import com.example.starter.orchestrator.api.SubmitPlanRequest.NodeSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 测试用合成计划构造器。
 */
final class PlanTestFactory {

    private PlanTestFactory() {
    }

    static SubmitPlanRequest plan(String planId, String version, String requestId,
                                  List<NodeSpec> nodes) {
        return new SubmitPlanRequest(planId, version, requestId, "test-plan", nodes);
    }

    static NodeSpec node(String nodeId, String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, false, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                null, null, null);
    }

    static NodeSpec nodeWithParams(String nodeId, Map<String, String> params,
                                   String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, false, params,
                dependsOn.length == 0 ? null : List.of(dependsOn),
                null, null, null);
    }

    static NodeSpec startFailing(String nodeId, String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, true, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                null, null, null);
    }

    static NodeSpec healthFailing(String nodeId, String compensation,
                                  String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, false, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                null, new HealthCheckSpec(true, "node unhealthy"),
                new CompensationSpec(compensation, false));
    }

    static NodeSpec withCompensation(String nodeId, String action, boolean simulateCompFail,
                                     String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, false, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                null, new HealthCheckSpec(false, "ok"),
                new CompensationSpec(action, simulateCompFail));
    }

    static NodeSpec withCondition(String nodeId, ConditionSpec condition,
                                  String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, 0L, false, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                condition, null, null);
    }

    static NodeSpec delayed(String nodeId, long delayMs, String... dependsOn) {
        return new NodeSpec(nodeId, nodeId, delayMs, false, Map.of(),
                dependsOn.length == 0 ? List.of() : List.of(dependsOn),
                null, null, null);
    }

    static ConditionSpec nodeSuccessCondition(String refNode) {
        return new ConditionSpec("NODE_SUCCESS", refNode, null, null);
    }

    static ConditionSpec paramEqualsCondition(String refNode, String param, String expected) {
        return new ConditionSpec("PARAM_EQUALS", refNode, param, expected);
    }

    static List<String> eventTypes(com.example.starter.orchestrator.api.PlanResponse response) {
        List<String> types = new ArrayList<>();
        response.events().forEach(e -> types.add(e.eventType()));
        return types;
    }
}
