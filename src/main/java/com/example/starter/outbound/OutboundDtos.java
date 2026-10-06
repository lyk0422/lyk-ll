package com.example.starter.outbound;

import jakarta.validation.constraints.NotBlank;

/**
 * 出库登记接口报文。
 */
public final class OutboundDtos {

    private OutboundDtos() {
    }

    /**
     * 出库登记请求。
     *
     * @param requestId 幂等请求标识
     * @param itemName 出库档案名称
     * @param borrower 借阅人
     */
    public record OutboundRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "出库档案名称不能为空") String itemName,
            @NotBlank(message = "借阅人不能为空") String borrower) {
    }

    /**
     * 出库登记响应。
     *
     * @param outboundId 出库记录主键
     * @param zoneCode 库区编码
     */
    public record OutboundResponse(long outboundId, String zoneCode) {
    }
}
