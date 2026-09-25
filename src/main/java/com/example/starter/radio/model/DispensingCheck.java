package com.example.starter.radio.model;

/**
 * 双人核对链记录。checkSeq 为核对次序（1 第一人 / 2 第二人），两名核对人员必须不同。
 */
public record DispensingCheck(
        long id,
        long dispensingId,
        String checker,
        int checkSeq,
        long checkedAtEpoch) {
}
