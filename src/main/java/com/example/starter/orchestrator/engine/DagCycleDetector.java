package com.example.starter.orchestrator.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * DAG 工具：检测显式依赖与条件引用构成的图中的环，并给出完整依赖路径。
 * 图方向：node -> 它依赖的前置节点（沿边表示“必须先完成”）。
 */
public final class DagCycleDetector {

    private DagCycleDetector() {
    }

    /**
     * 检测环。
     *
     * @param adjacency 节点 -> 它依赖的前置节点列表
     * @return 环的完整依赖路径（如 [A, B, C, A]，含义 A 依赖 B、B 依赖 C、C 依赖 A）；无环返回 null
     */
    public static List<String> findCycle(Map<String, List<String>> adjacency) {
        // 固定遍历顺序，保证报错路径稳定可断言
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        new ArrayList<>(adjacency.keySet()).stream().sorted().forEach(node -> {
            List<String> deps = new ArrayList<>(adjacency.getOrDefault(node, List.of()));
            Collections.sort(deps);
            sorted.put(node, deps);
        });

        Set<String> white = new HashSet<>(sorted.keySet());
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();
        List<String> stack = new ArrayList<>();

        for (String node : sorted.keySet()) {
            if (white.contains(node)) {
                List<String> cycle = dfs(node, sorted, white, gray, black, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return null;
    }

    private static List<String> dfs(String node, Map<String, List<String>> adjacency,
                                    Set<String> white, Set<String> gray, Set<String> black,
                                    List<String> stack) {
        white.remove(node);
        gray.add(node);
        stack.add(node);
        for (String dep : adjacency.getOrDefault(node, List.of())) {
            if (black.contains(dep)) {
                continue;
            }
            if (gray.contains(dep)) {
                // 找到回到 dep 的环：从栈中 dep 的位置截取，再闭合到 dep
                int start = stack.indexOf(dep);
                List<String> cycle = new ArrayList<>(stack.subList(start, stack.size()));
                cycle.add(dep);
                return cycle;
            }
            List<String> cycle = dfs(dep, adjacency, white, gray, black, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        stack.remove(stack.size() - 1);
        gray.remove(node);
        black.add(node);
        return null;
    }

    /**
     * Kahn 拓扑排序（无依赖的节点在前）。输入有环时抛出 IllegalStateException。
     */
    public static List<String> topologicalSort(Map<String, List<String>> adjacency) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String node : adjacency.keySet()) {
            indegree.putIfAbsent(node, 0);
            dependents.putIfAbsent(node, new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> e : adjacency.entrySet()) {
            String node = e.getKey();
            indegree.put(node, e.getValue().size());
            for (String dep : e.getValue()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node);
                indegree.putIfAbsent(dep, 0);
            }
        }
        Queue<String> ready = new LinkedList<>();
        indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(ready::add);

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String node = ready.poll();
            order.add(node);
            List<String> downstream = dependents.getOrDefault(node, List.of());
            List<String> newlyReady = new ArrayList<>();
            for (String d : downstream) {
                int remain = indegree.merge(d, -1, Integer::sum);
                if (remain == 0) {
                    newlyReady.add(d);
                }
            }
            newlyReady.stream().sorted().forEach(ready::add);
        }
        if (order.size() != indegree.size()) {
            throw new IllegalStateException("graph contains a cycle");
        }
        return order;
    }
}
