package com.example.starter.radio.model;

/**
 * 双人核对链记录。versionNo 为该签认所属计算版本；checkSeq 为核对次序（1 第一人 / 2 第二人），
 * 两名核对人员必须不同。变更生效后旧版本签认保留为证据但不再有效，新版本须重新双人核对。
 */
public record DispensingCheck(
        long id,
        long dispensingId,
        long versionNo,
        String checker,
        int checkSeq,
        long checkedAtEpoch) {
}
