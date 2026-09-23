package com.example.starter.rail.web;

import com.example.starter.rail.model.ConflictDetail;

import java.util.List;

/**
 * 审批因正长度重叠被拒绝。由控制器转为 409 并携带按区段、时间排序的冲突明细，
 * 不代表部分批准。
 */
public class ApprovalConflictException extends RuntimeException {

    private final String requestId;
    private final List<ConflictDetail> conflicts;

    public ApprovalConflictException(String requestId, List<ConflictDetail> conflicts) {
        super("approval conflicts with existing windows");
        this.requestId = requestId;
        this.conflicts = List.copyOf(conflicts);
    }

    public String getRequestId() {
        return requestId;
    }

    public List<ConflictDetail> getConflicts() {
        return conflicts;
    }
}
