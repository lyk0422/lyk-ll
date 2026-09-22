package com.example.starter.sync;

/**
 * 携带稳定错误码的业务异常。
 */
public class SyncException extends RuntimeException {

    private final SyncErrorCode code;

    public SyncException(SyncErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SyncErrorCode code() {
        return code;
    }
}
