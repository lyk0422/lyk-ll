package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 调拨异常人工核销请求，以 requestId 幂等；核销仅允许一次。
 */
public class TransferWriteOffRequest {

    /** 幂等请求标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 调拨单号。 */
    @NotBlank
    @Size(max = 64)
    private String orderNo;

    /** 核销原因。 */
    @NotBlank
    @Size(max = 512)
    private String reason;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
