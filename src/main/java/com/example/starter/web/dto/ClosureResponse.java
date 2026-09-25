package com.example.starter.web.dto;

/**
 * 销记响应；销记为终态，重复销记返回幂等成功。
 *
 * @param jobId    作业ID
 * @param closed   是否已销记
 * @param repeated 本次是否为重复销记
 * @param message  说明
 */
public record ClosureResponse(
        long jobId,
        boolean closed,
        boolean repeated,
        String message) {
}
