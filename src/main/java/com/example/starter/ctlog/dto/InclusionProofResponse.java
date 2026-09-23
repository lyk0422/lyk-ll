package com.example.starter.ctlog.dto;

import java.util.List;

/**
 * 包含证明响应。
 *
 * @param leafIndex 被证明的叶索引
 * @param treeSize  证明对应的树大小
 * @param leafHash  叶哈希（小写十六进制）
 * @param rootHash  该树大小下的根哈希（小写十六进制）
 * @param proof     审计路径，自叶向根，每个节点为小写十六进制
 */
public record InclusionProofResponse(long leafIndex, long treeSize, String leafHash,
                                     String rootHash, List<String> proof) {
}
