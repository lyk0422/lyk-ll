package com.example.starter.api.dto;

/**
 * DBC 导入结果。
 *
 * @param versionId   新版本主键
 * @param versionName 版本名称
 * @param requestId   幂等键（重放时返回同一版本）
 * @param replayed    true 表示本次为同参重放，未重复导入
 * @param messageCount 报文定义数量
 * @param signalCount 信号定义数量
 */
public record DbcImportResponse(
        long versionId,
        String versionName,
        String requestId,
        boolean replayed,
        int messageCount,
        int signalCount) {
}
