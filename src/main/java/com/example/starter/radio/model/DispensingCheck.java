package com.example.starter.radio.model;

/**
 * 双人核对链记录。checkSeq 为核对次序（1 第一人 / 2 第二人），两名核对人员必须不同。
 * versionNo 标识签认所属的分装计算版本；变更生效后旧版本签认保留但不再有效，不可被新版本复用。
 */
public record DispensingCheck(
        long id,
        long dispensingId,
        int versionNo,
        String checker,
        int checkSeq,
        long checkedAtEpoch) {
}
