package com.example.starter.legality.api;

/**
 * 封板结果。重复封板返回幂等成功。
 *
 * @param batchId   批次ID
 * @param legal     终态整组判定
 * @param replayed  是否为重复封板的幂等返回
 */
public record FinalizeResponse(
        String batchId,
        boolean legal,
        boolean replayed) {
}
