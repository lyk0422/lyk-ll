package com.example.starter.runway;

import com.example.starter.runway.dto.PlanConflictView;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 例行计划与合成航班窗存在正长度相交的业务拒绝（HTTP 422），携带结构化冲突证据。
 */
public class PlanConflictException extends ApiException {

    private final int calendarVersion;
    private final transient List<PlanConflictView> conflicts;

    public PlanConflictException(int calendarVersion, List<PlanConflictView> conflicts, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_FLIGHT_CONFLICT", message);
        this.calendarVersion = calendarVersion;
        this.conflicts = List.copyOf(conflicts);
    }

    public int getCalendarVersion() {
        return calendarVersion;
    }

    public List<PlanConflictView> getConflicts() {
        return conflicts;
    }
}
