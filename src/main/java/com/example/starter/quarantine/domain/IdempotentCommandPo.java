package com.example.starter.quarantine.domain;

import java.time.Instant;

/**
 * 幂等命令行：记录命令键、全字段指纹与首次结果 JSON。
 */
public class IdempotentCommandPo {

    private String commandKey;
    private String fingerprint;
    private String resultJson;
    private Instant createdAt;

    public String getCommandKey() {
        return commandKey;
    }

    public void setCommandKey(String commandKey) {
        this.commandKey = commandKey;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
