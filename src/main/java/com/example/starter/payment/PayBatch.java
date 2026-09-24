package com.example.starter.payment;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日切批次。
 *
 * @param id        批次ID
 * @param batchDate 批次日期（UTC 日切归属日）
 * @param status    状态：OPEN 开放 / CLOSED 已封账（终态）
 * @param closedAt  封账时间（UTC），未封账为 null
 */
public record PayBatch(long id, LocalDate batchDate, String status, Instant closedAt) {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    public boolean isClosed() {
        return CLOSED.equals(status);
    }
}
