package com.example.starter.web.dto;

import java.util.List;

/**
 * 分车清单写操作（创建/撤销/发车核销）响应：回放车单冻结清单、当前版本与状态，
 * 并附按头只勾稽的批次出厂进度（病害头只不计入未出厂）。
 *
 * @param batchNo       屠宰批次号
 * @param truckNo       合成车号
 * @param version       操作后车单版本：创建为 1，撤销/发车成功加 1
 * @param status        车单状态：RESERVED/RELEASED/REVOKED
 * @param frozenHeads   冻结头只标识集合（创建时给定顺序，发车与撤销均不可改）
 * @param createdAt     清单创建冻结时刻（UTC ISO-8601）
 * @param revokedAt     整体撤销时刻，NULL 表示未撤销
 * @param dispatchedAt  发车核销时刻，NULL 表示未发车
 * @param progress      批次按头只勾稽的出厂进度
 */
public record TruckManifestResponse(
        String batchNo,
        String truckNo,
        int version,
        String status,
        List<String> frozenHeads,
        String createdAt,
        String revokedAt,
        String dispatchedAt,
        TruckProgress progress) {

    /**
     * 批次出厂进度：仅按合格（QUALIFIED）头只勾稽，病害处置头只不计入未出厂。
     *
     * @param splitModeEnabled 是否启用分车模式（存在任意车单含已撤销）
     * @param qualifiedTotal   本批合格头只总数
     * @param shippedCount     已随车核销出厂的合格头只数
     * @param unshippedCount   尚未出厂的合格头只数（含预占未发车）
     * @param batchReleased    批次是否已到最终出厂状态 RELEASED（恰全部合格头只核销完）
     */
    public record TruckProgress(
            boolean splitModeEnabled,
            int qualifiedTotal,
            int shippedCount,
            int unshippedCount,
            boolean batchReleased) {
    }
}
