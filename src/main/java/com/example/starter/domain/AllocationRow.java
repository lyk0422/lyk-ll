package com.example.starter.domain;

/**
 * 分摊明细行。round 为分摊轮次：1 为首次分摊，超时重分摊递增。
 */
public record AllocationRow(long id, long commandId, int round, long turbineId,
                            int availableKw, int minKw, int headroomKw, int targetKw) {
}
