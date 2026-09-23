package com.example.starter.ctlog.dto;

import java.util.List;

/**
 * 证明验证请求；proof 为自叶向根的审计路径（十六进制）。
 *
 * @param leafHash   待验证叶哈希（十六进制）
 * @param leafIndex  叶索引
 * @param treeSize   树大小
 * @param rootHash   期望根哈希（十六进制）
 * @param proof      审计路径
 */
public record VerifyInclusionRequest(String leafHash, long leafIndex, long treeSize,
                                     String rootHash, List<String> proof) {
}
