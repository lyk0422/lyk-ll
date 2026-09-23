package com.example.starter.audit.domain;

/**
 * 阶段业务失败（非中断），编排层将阶段与任务置为 FAILED。
 */
public class StageFailureException extends Exception {

    public StageFailureException(String message) {
        super(message);
    }
}
