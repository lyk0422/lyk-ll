package com.example.starter.orchestrator;

/**
 * 计划当前状态不允许目标操作（如对已终结计划再次执行）时抛出，映射 HTTP 409。
 */
public class PlanStateException extends RuntimeException {

    public PlanStateException(String message) {
        super(message);
    }
}
