package com.example.starter.service;

/**
 * 业务执行结果：携带首次（或重放的）HTTP 状态码与响应体。
 *
 * @param status 首次响应的 HTTP 状态码
 * @param body   响应体（错误时为错误载荷）
 * @param replay true 表示这是同 requestId 同参的幂等重放
 */
public record Outcome(int status, Object body, boolean replay) {

    public static Outcome of(int status, Object body) {
        return new Outcome(status, body, false);
    }

    public static Outcome replay(int status, Object body) {
        return new Outcome(status, body, true);
    }
}
