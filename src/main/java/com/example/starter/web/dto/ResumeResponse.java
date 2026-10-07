package com.example.starter.web.dto;

/**
 * 续行许可响应；仅结束本次阻断，作业仍为发车确认态，列尾装置继续占用。
 *
 * @param incidentId      处置单ID
 * @param jobId           所属作业ID
 * @param resumed         是否准许续行
 * @param incidentVersion 处置单新版本
 * @param message         提示信息
 */
public record ResumeResponse(
        long incidentId,
        long jobId,
        boolean resumed,
        int incidentVersion,
        String message) {
}
