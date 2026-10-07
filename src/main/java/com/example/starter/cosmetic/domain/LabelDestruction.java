package com.example.starter.cosmetic.domain;

import java.time.Instant;

/**
 * 未领标签销毁登记（持久化对象的内存镜像，字段口径与 label_destruction 表一致）。
 *
 * <p>仅未领部分可销毁并留证；已领部分不能销毁退回余额。</p>
 */
public record LabelDestruction(
        Long id,
        String printRunNo,
        int quantity,
        String reason,
        String requestId,
        Instant destroyedAtUtc
) {
}
