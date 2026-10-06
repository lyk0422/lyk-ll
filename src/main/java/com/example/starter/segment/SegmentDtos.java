package com.example.starter.segment;

import jakarta.validation.constraints.NotBlank;

/**
 * 路段接口出入参。
 */
public final class SegmentDtos {

    private SegmentDtos() {
    }

    /** 建档请求；requestId 为幂等键。 */
    public record CreateSegmentRequest(@NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "路段名称不能为空") String name) {
    }

    /** 路段响应。 */
    public record SegmentResponse(long id, String name) {
    }
}
