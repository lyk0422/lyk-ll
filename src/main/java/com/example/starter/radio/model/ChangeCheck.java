package com.example.starter.radio.model;

/**
 * 变更单双人复核链记录。reviewSeq 为复核次序（1 第一人 / 2 第二人），两名复核人员必须不同。
 */
public record ChangeCheck(
        long id,
        long changeOrderId,
        String reviewer,
        int reviewSeq,
        long reviewedAtEpoch) {
}
