package com.example.starter.recheck;

import java.time.LocalDateTime;

/**
 * 留样记录：复检不合格转监督处置时登记，留样 48 小时。时间均为 UTC。
 */
public record Retention(
        long id,
        long batchId,
        LocalDateTime retainUntil,
        LocalDateTime createdAt) {
}
