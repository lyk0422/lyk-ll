package com.example.starter.audit.domain;

/**
 * 阶段执行过程中收到中断信号，编排层将阶段与任务置为 INTERRUPTED。
 */
public class StageInterruptedException extends Exception {

    public StageInterruptedException(String message) {
        super(message);
    }
}
