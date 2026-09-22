package com.example.starter.orchestrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 工具：拓扑排序与循环检测。依赖边包含显式依赖与条件谓词引用产生的隐式边。
 */
public final class DagUtils {

    private DagUtils() {
    }

    /**
     * 汇总每个节点的前置节点集合（显式依赖 + 条件引用）。
     *
     * @param explicitDeps 节点到显式依赖列表的映射
     * @param conditionRefs 节点到条件引用节点的映射（无条件则不出现）
     * @return 节点到前置集合的映射
     */
    public static Map<String, Set<String>> prerequisites(
            Map<String, List<String>> explicitDeps, Map<String, String> conditionRefs) {
        Map<String, Set<String>> prereqs = new LinkedHashMap<>();
        explicitDeps.forEach((node, deps) -> prereqs.computeIfAbsent(node, k -> new HashSet<>()).addAll(deps));
        conditionRefs.forEach((node, ref) -> {
            if (ref != null) {
                prereqs.computeIfAbsent(node, k -> new HashSet<>()).add(ref);
            }
        });
        return prereqs;
    }

    /**
     * 检测循环并返回完整依赖路径（首尾相同，如 A -> B -> A）；无环返回 null。
     */
    public static List<String> findCycle(Map<String, Set<String>> prereqs) {
        Map<String, Integer> color = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String node : prereqs.keySet()) {
            List<String> cycle = dfs(node, prereqs, color, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static List<String> dfs(String node, Map<String, Set<String>> prereqs,
            Map<String, Integer> color, Deque<String> stack) {
        Integer mark = color.get(node);
        if (mark != null && mark == 1) {
            List<String> path = new ArrayList<>(stack);
            int from = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(from, path.size()));
            cycle.add(node);
            return cycle;
        }
        if (mark != null && mark == 2) {
            return null;
        }
        color.put(node, 1);
        stack.addLast(node);
        for (String pre : prereqs.getOrDefault(node, Set.of())) {
            if (prereqs.containsKey(pre)) {
                List<String> cycle = dfs(pre, prereqs, color, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.removeLast();
        color.put(node, 2);
        return null;
    }

    /**
     * 拓扑排序，前置节点排在依赖它的节点之前。调用前须确保无环。
     */
    public static List<String> topoOrder(Map<String, Set<String>> prereqs) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        prereqs.forEach((node, pres) -> {
            indegree.putIfAbsent(node, 0);
            for (String pre : pres) {
                if (prereqs.containsKey(pre)) {
                    indegree.merge(node, 1, Integer::sum);
                    dependents.computeIfAbsent(pre, k -> new ArrayList<>()).add(node);
                }
            }
        });
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((node, deg) -> {
            if (deg == 0) {
                ready.addLast(node);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            order.add(node);
            for (String dep : dependents.getOrDefault(node, List.of())) {
                if (indegree.merge(dep, -1, Integer::sum) == 0) {
                    ready.addLast(dep);
                }
            }
        }
        return order;
    }
}
