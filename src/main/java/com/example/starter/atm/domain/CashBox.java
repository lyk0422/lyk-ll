package com.example.starter.atm.domain;

/**
 * 款箱（合成数据）。容量与装填金额单位：分。
 */
public record CashBox(
        Long id,
        String boxNo,
        long atmId,
        long capacity,
        long loadedAmount,
        String status,
        long createdAt
) {
}
