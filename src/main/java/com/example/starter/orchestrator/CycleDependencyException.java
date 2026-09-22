package com.example.starter.orchestrator;

import java.util.List;

/**
 * 执行前检测到显式依赖或条件引用导致的循环时抛出，映射 HTTP 400。
 * 携带完整循环依赖路径。
 */
public class CycleDependencyException extends RuntimeException {

    private final List<String> cyclePath;

    public CycleDependencyException(List<String> cyclePath) {
        super("检测到循环依赖: " + String.join(" -> ", cyclePath));
        this.cyclePath = List.copyOf(cyclePath);
    }

    public List<String> getCyclePath() {
        return cyclePath;
    }
}
