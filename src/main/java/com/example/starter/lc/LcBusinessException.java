package com.example.starter.lc;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带错误码与 HTTP 状态，不回滚也属于正常业务结果时由上层自行决定是否落幂等记录。
 */
public class LcBusinessException extends RuntimeException {

    private final LcErrorCode errorCode;

    private final HttpStatus httpStatus;

    public LcBusinessException(LcErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public LcErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
