package com.example.starter.repo;

import java.time.Instant;

/**
 * 押运员行。frozen=1 表示因名下长短款逾期未销账被升级冻结，不得承接新押运任务。
 */
public record Custodian(
        Long id,
        String code,
        String name,
        boolean frozen,
        Instant createdAt
) {
}
