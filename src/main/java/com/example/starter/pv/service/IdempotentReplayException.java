package com.example.starter.pv.service;

/**
 * 同键同参重放：携带首次成功结果，由 Web 层直接返回，不重复执行业务。
 */
public class IdempotentReplayException extends RuntimeException {

    private final transient Object replayResponse;

    public IdempotentReplayException(Object replayResponse) {
        super("idempotent-replay");
        this.replayResponse = replayResponse;
    }

    public Object getReplayResponse() {
        return replayResponse;
    }
}
