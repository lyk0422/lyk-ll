package com.example.starter.scene.exception;

/**
 * 幂等键冲突：相同 requestId 携带了不同参数，HTTP 409。
 */
public class RequestConflictException extends RuntimeException {

    private final String requestId;

    public RequestConflictException(String requestId) {
        super("requestId " + requestId + " was already used with different parameters");
        this.requestId = requestId;
    }

    /**
     * 冲突的幂等请求 ID。
     */
    public String getRequestId() {
        return requestId;
    }
}
