package com.example.starter.audit.engine;

/**
 * 阶段执行失败异常：携带阶段名与失败原因。
 */
public class StageExecutionException extends RuntimeException {

    private final String stage;

    public StageExecutionException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
