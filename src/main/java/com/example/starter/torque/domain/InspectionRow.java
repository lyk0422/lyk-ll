package com.example.starter.torque.domain;

import java.time.Instant;

/** inspection_sign 表行记录。 */
public record InspectionRow(
        long id,
        long cardId,
        String signType,
        String inspector,
        Instant signedAt
) {
}
