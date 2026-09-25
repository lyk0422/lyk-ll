package com.example.starter.torque.domain;

import java.time.Instant;

/** witness_event 表行记录（见证链）。 */
public record WitnessEventRow(
        long id,
        long cardId,
        String pointCode,
        String eventType,
        String witness,
        Instant eventAt,
        String detail
) {
}
