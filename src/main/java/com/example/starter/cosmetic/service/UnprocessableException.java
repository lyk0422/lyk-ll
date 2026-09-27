package com.example.starter.cosmetic.service;

import java.util.List;

/**
 * 业务拒绝异常，映射为 HTTP 422：儿童备案审查缺项（一次列全）、状态不允许的业务操作等。
 * 抛出后事务回滚，幂等键不被占用（失败不占键）。
 */
public class UnprocessableException extends RuntimeException {

    /** 业务错误码（机器可读）。 */
    private final String code;

    /** 缺项/拒绝原因清单（一次列全）。 */
    private final List<String> problems;

    public UnprocessableException(String code, String message, List<String> problems) {
        super(message);
        this.code = code;
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public String code() {
        return code;
    }

    public List<String> problems() {
        return problems;
    }
}
