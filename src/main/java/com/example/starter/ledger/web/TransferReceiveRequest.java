package com.example.starter.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 到站签收请求（调入方签收），以 requestId 幂等。
 */
public class TransferReceiveRequest {

    /** 幂等请求标识。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    /** 调拨单号。 */
    @NotBlank
    @Size(max = 64)
    private String orderNo;

    /** 签收站点编码，须与调拨单调入方一致。 */
    @NotBlank
    @Size(max = 64)
    private String toStation;

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

    public String getToStation() {
        return toStation;
    }

    public void setToStation(String toStation) {
        this.toStation = toStation;
    }
}
