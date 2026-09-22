package com.example.starter.orchestrator;

/**
 * 计划不存在时抛出，映射 HTTP 404。
 */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(String planId) {
        super("计划不存在: " + planId);
    }
}
