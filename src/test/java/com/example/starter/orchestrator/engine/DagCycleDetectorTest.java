package com.example.starter.orchestrator.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagCycleDetectorTest {

    @Test
    void noCycleReturnsNull() {
        Map<String, List<String>> graph = Map.of(
                "a", List.of("b", "c"),
                "b", List.of("c"),
                "c", List.of()
        );
        assertThat(DagCycleDetector.findCycle(graph)).isNull();
    }

    @Test
    void detectsExplicitCycleWithFullPath() {
        // a 依赖 b，b 依赖 c，c 依赖 a
        Map<String, List<String>> graph = Map.of(
                "a", List.of("b"),
                "b", List.of("c"),
                "c", List.of("a")
        );
        List<String> cycle = DagCycleDetector.findCycle(graph);
        assertThat(cycle).isNotNull();
        assertThat(cycle).hasSize(4);
        assertThat(cycle.get(0)).isEqualTo(cycle.get(cycle.size() - 1));
        assertThat(cycle).containsExactly("a", "b", "c", "a");
    }

    @Test
    void detectsSelfReferenceCycle() {
        Map<String, List<String>> graph = Map.of("a", List.of("a"));
        assertThat(DagCycleDetector.findCycle(graph)).containsExactly("a", "a");
    }

    @Test
    void detectsCycleIntroducedByConditionReference() {
        // 显式边无环，条件引用 b->a 与 a->b 构成隐式环
        Map<String, List<String>> graph = Map.of(
                "a", List.of("b"),
                "b", List.of("a")
        );
        assertThat(DagCycleDetector.findCycle(graph)).isNotNull();
    }

    @Test
    void topologicalSortPutsPrerequisitesFirst() {
        Map<String, List<String>> graph = Map.of(
                "ui", List.of("service"),
                "service", List.of("db"),
                "db", List.of()
        );
        assertThat(DagCycleDetector.topologicalSort(graph)).containsExactly("db", "service", "ui");
    }

    @Test
    void topologicalSortThrowsOnCycle() {
        Map<String, List<String>> graph = Map.of(
                "a", List.of("b"),
                "b", List.of("a")
        );
        assertThatThrownBy(() -> DagCycleDetector.topologicalSort(graph))
                .isInstanceOf(IllegalStateException.class);
    }
}
