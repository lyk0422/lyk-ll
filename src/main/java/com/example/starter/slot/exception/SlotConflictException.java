package com.example.starter.slot.exception;

import com.example.starter.slot.dto.ConflictView;

import java.util.List;

/**
 * 整周协调冲突：系列覆盖周中至少一周的同一时刻已被其他航司占用，整体系列回滚。
 */
public class SlotConflictException extends RuntimeException {

    private final transient List<ConflictView> conflicts;

    public SlotConflictException(List<ConflictView> conflicts) {
        super("slot conflict");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<ConflictView> getConflicts() {
        return conflicts;
    }
}
