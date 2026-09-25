package com.example.starter.web.dto;

/**
 * 回执勾稽条目：按化制/销毁类别对比判定数与回执头只数，恰等视为勾稽一致。
 *
 * @param category      判定类别：RENDERING 化制 / DESTRUCTION 销毁
 * @param judgedCount   该类别的判定头只数
 * @param receivedCount 该类别已登记回执的头只数合计（不含迟到回执）
 * @param matched       是否恰等
 */
public record ReconciliationEntry(
        String category,
        int judgedCount,
        int receivedCount,
        boolean matched) {
}
