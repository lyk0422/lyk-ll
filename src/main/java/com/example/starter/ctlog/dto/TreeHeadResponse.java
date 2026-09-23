package com.example.starter.ctlog.dto;

/**
 * 树头响应。
 *
 * @param treeSize  树大小（叶数量）
 * @param rootHash  根哈希（小写十六进制）；零大小为 SHA-256 空输入
 * @param timestamp 树头生成时间（epoch 毫秒，UTC）
 */
public record TreeHeadResponse(long treeSize, String rootHash, long timestamp) {
}
