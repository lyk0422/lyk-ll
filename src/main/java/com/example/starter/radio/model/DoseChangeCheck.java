package com.example.starter.radio.model;

/**
 * 变更候选双人复核链记录。checkSeq 为复核次序（1 第一人 / 2 第二人），两名复核人员必须不同。
 */
public record DoseChangeCheck(
        long id,
        long changeOrderId,
        String checker,
        int checkSeq,
        long checkedAtEpoch) {
}
