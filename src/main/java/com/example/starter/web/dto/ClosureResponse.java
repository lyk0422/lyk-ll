package com.example.starter.web.dto;

/**
 * 结案结果。
 *
 * @param requestId 结案请求幂等键
 * @param order     调度单最新视图
 * @param repeated  true 表示重复结案，按幂等成功返回首次结论
 */
public record ClosureResponse(
        String requestId,
        OrderResponse order,
        boolean repeated
) {
}
