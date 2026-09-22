package com.example.starter.bag.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 模拟写入中断 / 索引损坏的请求，仅用于恢复能力的业务测试入口。
 *
 * @param topic   Topic 名称
 * @param segIndex 目标分段序号
 * @param mode    故障模式：DROP_INDEX 删除段内索引 / INCOMPLETE_TAIL 追加无索引的不完整尾消息 / MARK_DAMAGED 仅标记损坏
 * @param tailSize INCOMPLETE_TAIL 模式下追加的不完整消息条数
 */
public record CorruptSegmentRequest(
        @NotBlank String topic,
        int segIndex,
        @NotNull FaultMode mode,
        @Positive Integer tailSize) {

    /** 故障模拟模式。 */
    public enum FaultMode {
        /** 删除该段全部索引行，模拟索引损坏 */
        DROP_INDEX,
        /** 直接写入若干不带索引、不更新链校验的尾消息，模拟写入中断 */
        INCOMPLETE_TAIL,
        /** 仅把段标记为 DAMAGED，不破坏数据 */
        MARK_DAMAGED
    }
}
