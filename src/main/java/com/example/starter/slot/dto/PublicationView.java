package com.example.starter.slot.dto;

import java.util.List;

/**
 * 分配表快照发布结果。
 *
 * @param publishedCount 快照中的占用记录条数
 * @param alreadyPublished 本次是否为重复发布（幂等成功）
 * @param snapshot 快照内容（时刻/周/航司/航班号/类型）
 */
public record PublicationView(int publishedCount, boolean alreadyPublished,
                              List<SnapshotEntry> snapshot) {

    /**
     * 快照条目。
     *
     * @param slotTime 时刻（HH:mm）
     * @param week     周序号
     * @param airline  航司二字码
     * @param flightNo 航班号
     * @param type     占用类型：RETAINED 或 POOL
     */
    public record SnapshotEntry(String slotTime, int week, String airline,
                                String flightNo, String type) {
    }
}
