package com.example.starter.cutover.support;

/**
 * 冲突：同键异参重放、终态冲突等，映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    /** 冲突类型代码，便于调用方区分。 */
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
