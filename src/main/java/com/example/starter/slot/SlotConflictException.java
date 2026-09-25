package com.example.starter.slot;

import com.example.starter.slot.dto.SlotConflictView;

import java.util.List;

/**
 * 时刻冲突异常：申请的时刻/周已被其他航司占用，或同 requestId 携带了不同参数。
 */
public class SlotConflictException extends RuntimeException {

    private final String code;
    private final List<SlotConflictView> conflicts;

    public SlotConflictException(String code, String message, List<SlotConflictView> conflicts) {
        super(message);
        this.code = code;
        this.conflicts = conflicts;
    }

    public String getCode() {
        return code;
    }

    public List<SlotConflictView> getConflicts() {
        return conflicts;
    }
}
