package com.example.starter.roaming.web.dto;

/**
 * 清分批封存请求：封存为终态，重复封存返回首次清分单。
 *
 * @param requestId 幂等键
 * @param batchNo   批次号
 */
public record SealRequest(
        String requestId,
        String batchNo) {
}
