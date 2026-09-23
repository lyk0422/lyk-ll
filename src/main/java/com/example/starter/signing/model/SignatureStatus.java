package com.example.starter.signing.model;

/**
 * 签名记录状态。修订后旧记录保留为 {@link #INVALIDATED} 作为失效历史。
 */
public enum SignatureStatus {

    /** 当前修订下有效。 */
    VALID,
    /** 已被后续修订失效，记录永久保留。 */
    INVALIDATED
}
