package com.example.starter.ctlog.dto;

import java.util.List;

/**
 * 一致性证明验证请求。
 */
public record VerifyConsistencyRequest(long firstTreeSize, long secondTreeSize,
                                       String firstRootHash, String secondRootHash,
                                       List<String> proof) {
}
