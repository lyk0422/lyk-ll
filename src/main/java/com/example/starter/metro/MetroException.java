package com.example.starter.metro;

/**
 * 业务异常基类：携带 HTTP 状态码，由全局异常处理器转换为错误响应。
 */
public class MetroException extends RuntimeException {

    private final int status;

    public MetroException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** 同 requestId 携带不同业务参数：返回 409，且不重复入账。 */
    public static class IdempotencyConflict extends MetroException {
        public IdempotencyConflict(String requestId) {
            super(409, "requestId 已存在但业务参数不一致: " + requestId);
        }
    }

    /** 交易流水号重复：拒绝入账，返回 409。 */
    public static class DuplicateTransaction extends MetroException {
        public DuplicateTransaction(String txNo) {
            super(409, "交易流水号重复: " + txNo);
        }
    }

    /** 票卡无进行中（OPEN/PROVISIONAL）的行程，无法出闸。 */
    public static class TripNotFound extends MetroException {
        public TripNotFound(String ticketId) {
            super(404, "票卡无进行中行程: " + ticketId);
        }
    }

    /** 站点不存在等请求参数错误。 */
    public static class InvalidRequest extends MetroException {
        public InvalidRequest(String message) {
            super(400, message);
        }
    }
}
