package com.example.starter.robot;

import java.util.List;

/**
 * 编译流程业务异常。
 */
public class CompileException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<String> chain;
    private final String requestId;

    public CompileException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public CompileException(ErrorCode errorCode, String message, List<String> chain) {
        this(errorCode, message, chain, null);
    }

    private CompileException(ErrorCode errorCode, String message, List<String> chain, String requestId) {
        super(message);
        this.errorCode = errorCode;
        this.chain = List.copyOf(chain);
        this.requestId = requestId;
    }

    /** 返回携带指定 requestId 的等价异常，供服务边界回填。 */
    public CompileException withRequestId(String requestId) {
        return new CompileException(errorCode, getMessage(), chain, requestId);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<String> chain() {
        return chain;
    }

    public String requestId() {
        return requestId;
    }
}
