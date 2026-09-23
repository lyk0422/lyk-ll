package com.example.starter.exception;

import com.example.starter.api.dto.ConflictView;

import java.util.List;

/**
 * 审批/抢占因时间窗冲突被拒绝。HTTP 409，响应体携带按区段与时间排序的冲突明细。
 */
public class ConflictException extends RuntimeException {

    private final List<ConflictView> conflicts;

    public ConflictException(String message, List<ConflictView> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<ConflictView> getConflicts() {
        return conflicts;
    }
}
