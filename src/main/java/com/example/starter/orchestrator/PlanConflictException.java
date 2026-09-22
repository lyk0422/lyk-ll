package com.example.starter.orchestrator;

/**
 * 同一 requestId 携带不同计划内容重复提交时抛出，映射 HTTP 409。
 */
public class PlanConflictException extends RuntimeException {

    public PlanConflictException(String message) {
        super(message);
    }
}
