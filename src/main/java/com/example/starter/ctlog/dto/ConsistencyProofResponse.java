package com.example.starter.ctlog.dto;

import java.util.List;

/**
 * 一致性证明响应。
 *
 * @param firstTreeSize  旧树大小
 * @param secondTreeSize 新树大小
 * @param firstRootHash  旧树根哈希（小写十六进制）
 * @param secondRootHash 新树根哈希（小写十六进制）
 * @param proof          一致性证明节点列表，小写十六进制
 */
public record ConsistencyProofResponse(long firstTreeSize, long secondTreeSize,
                                       String firstRootHash, String secondRootHash,
                                       List<String> proof) {
}
