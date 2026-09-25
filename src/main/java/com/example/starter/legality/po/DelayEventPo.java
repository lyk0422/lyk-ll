package com.example.starter.legality.po;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 延误延长事件持久化行。
 */
public class DelayEventPo {

    private Long id;
    private Long dutyRecordId;
    private String requestId;
    private int extensionMinutes;
    private boolean late;
    private LocalDate eventDayUtc;
    private String note;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDutyRecordId() {
        return dutyRecordId;
    }

    public void setDutyRecordId(Long dutyRecordId) {
        this.dutyRecordId = dutyRecordId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getExtensionMinutes() {
        return extensionMinutes;
    }

    public void setExtensionMinutes(int extensionMinutes) {
        this.extensionMinutes = extensionMinutes;
    }

    public boolean isLate() {
        return late;
    }

    public void setLate(boolean late) {
        this.late = late;
    }

    public LocalDate getEventDayUtc() {
        return eventDayUtc;
    }

    public void setEventDayUtc(LocalDate eventDayUtc) {
        this.eventDayUtc = eventDayUtc;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
