package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.LabelDestruction;

import java.time.Instant;

/**
 * 未领标签销毁登记响应。时间均为 UTC。
 */
public record LabelDestructionResponse(
        Long destructionId,
        String printRunNo,
        int quantity,
        String reason,
        Instant destroyedAtUtc) {

    /** 由领域记录构造响应。 */
    public static LabelDestructionResponse from(LabelDestruction d) {
        return new LabelDestructionResponse(d.id(), d.printRunNo(), d.quantity(),
                d.reason(), d.destroyedAtUtc());
    }
}
