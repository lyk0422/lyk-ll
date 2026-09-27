package com.example.starter.dao;

import java.time.Instant;

/**
 * 积分变动流水行：受控出水时与积分账户、潜次状态同事务写入。
 */
public record ResidualMovementRow(
        Long id,
        String diverCode,
        String diveRequestId,
        int planVersion,
        int preDivePoints,
        int bottomMin,
        int residualPoints,
        Instant surfacedAtUtc,
        Instant createdAtUtc) {
}
