package com.example.starter.torque.domain;

import java.math.BigDecimal;

/** fastener_spec 表行记录。 */
public record FastenerSpecRow(
        long id,
        long cardId,
        String pointCode,
        BigDecimal nominalTorque,
        boolean hPoint
) {
}
