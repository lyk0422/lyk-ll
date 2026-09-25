package com.example.starter.dto;

/**
 * 执行回报响应。replayed=true 表示同键同参重放；late=true 表示指令已封存、仅登记迟到事件。
 */
public record ReportResponse(
        String requestId,
        String orderNo,
        String turbineCode,
        String measuredAt,
        int powerKw,
        boolean late,
        boolean replayed) {

    /** 同键同参重放时标记 replayed=true，其余内容与首次响应一致。 */
    public ReportResponse asReplay() {
        return new ReportResponse(requestId, orderNo, turbineCode, measuredAt, powerKw,
                late, true);
    }
}
