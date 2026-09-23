package com.example.starter.compiler.core;

/**
 * 编译过程中的业务异常；携带错误码与面向调用方的中文说明。
 */
public class CompilerException extends RuntimeException {

    private final ErrorCode errorCode;

    public CompilerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
