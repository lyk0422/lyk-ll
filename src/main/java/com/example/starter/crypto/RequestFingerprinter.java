package com.example.starter.crypto;

import org.springframework.stereotype.Component;

/**
 * 请求指纹计算器：对族标识、请求键、客户端标识与令牌哈希做 SHA-256，
 * 用于安全事件记录与同键异参冲突识别。
 */
@Component
public class RequestFingerprinter {

    private final TokenHasher tokenHasher;

    public RequestFingerprinter(TokenHasher tokenHasher) {
        this.tokenHasher = tokenHasher;
    }

    /**
     * 计算请求指纹：SHA-256(familyId | requestId | clientId | tokenHash)。
     */
    public String fingerprint(String familyId, String requestId, String clientId, String tokenHash) {
        String canonical = familyId + "|" + requestId + "|" + clientId + "|" + tokenHash;
        return tokenHasher.sha256(canonical);
    }
}
