package com.example.starter.sync.domain;

/**
 * 消息接收结果。status 区分接受与丢弃；丢弃时 reason 非空。
 */
public record IngestOutcome(
        Status status,
        SensorSample sample,
        DiscardReason reason) {

    public enum Status {
        ACCEPTED,
        DISCARDED
    }

    public static IngestOutcome accepted(SensorSample sample) {
        return new IngestOutcome(Status.ACCEPTED, sample, null);
    }

    public static IngestOutcome discarded(SensorSample sample, DiscardReason reason) {
        return new IngestOutcome(Status.DISCARDED, sample, reason);
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}
