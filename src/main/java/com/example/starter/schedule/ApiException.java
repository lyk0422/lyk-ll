package com.example.starter.schedule;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带 HTTP 状态、消息与可选违例明细。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<ScheduleResponses.Violation> violations;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<ScheduleResponses.Violation> violations) {
        super(message);
        this.status = status;
        this.violations = violations;
    }

    public HttpStatus status() {
        return status;
    }

    public List<ScheduleResponses.Violation> violations() {
        return violations;
    }
}
