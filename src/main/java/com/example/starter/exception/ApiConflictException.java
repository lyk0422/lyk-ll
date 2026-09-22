package com.example.starter.exception;

/**
 * 请求与当前资源状态冲突（如重复 requestId 异参、版本名已存在），映射 HTTP 409。
 */
public class ApiConflictException extends RuntimeException {

    /** 机器可读的冲突代码。 */
    private final String code;

    public ApiConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
