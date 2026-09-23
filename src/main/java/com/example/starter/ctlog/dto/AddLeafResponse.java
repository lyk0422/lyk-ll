package com.example.starter.ctlog.dto;

/**
 * 追加叶响应。
 *
 * @param leafIndex 分配的叶索引（重复 requestId 返回首次索引）
 * @param entryHash 条目摘要（DER 的 SHA-256，小写十六进制）
 * @param idempotent 是否为幂等命中（同键同条目重复提交）
 */
public record AddLeafResponse(long leafIndex, String entryHash, boolean idempotent) {
}
