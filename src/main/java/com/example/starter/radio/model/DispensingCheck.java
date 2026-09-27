package com.example.starter.radio.model;

/**
 * 双人核对链记录。version 为签认所属计算版本号，旧版本签认保留但对新版本无效；
 * checkSeq 为核对次序（1 第一人 / 2 第二人），两名核对人员必须不同。
 */
public record DispensingCheck(
        long id,
        long dispensingId,
        int version,
        String checker,
        int checkSeq,
        long checkedAtEpoch) {
}
