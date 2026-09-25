package com.example.starter.deicing.po;

/**
 * 幂等操作台账行（idempotent_operation）。
 */
public class IdempotentOperationPO {

    /** 申请幂等键。 */
    private String requestId;
    /** 操作类型。 */
    private String operation;
    /** 业务字段指纹。 */
    private String fingerprint;
    /** 首次响应 HTTP 状态码。 */
    private int responseStatus;
    /** 首次响应体 JSON。 */
    private String responseBody;
    /** 首次受理时刻。 */
    private long createdTs;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(int responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    public void setCreatedTs(long createdTs) {
        this.createdTs = createdTs;
    }
}
