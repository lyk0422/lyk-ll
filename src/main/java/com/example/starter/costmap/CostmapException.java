package com.example.starter.costmap;

/**
 * 业务可预测错误基类；映射为稳定的 HTTP 状态码与错误码。
 */
public class CostmapException extends RuntimeException {

    private final String code;

    public CostmapException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
