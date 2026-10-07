package com.example.starter.web.dto;

import java.util.List;

/**
 * 分车出厂查询视图：给出每车冻结清单与状态、按头只勾稽的已出厂/未出厂头只及批次进度。
 * 未启用分车的批次返回 enabled=false 的空车单视图。
 *
 * @param batchNo     屠宰批次号
 * @param batchStatus 批次当前状态
 * @param enabled     是否启用分车模式（存在任意车单，含已撤销）
 * @param qualified   本批合格头只标识集合（取自判定明细 QUALIFIED）
 * @param shipped     已随车核销出厂的合格头只标识集合
 * @param unshipped   尚未出厂的合格头只标识集合（含已预占未发车）
 * @param manifests   每车冻结清单，按车号排序
 * @param progress    批次按头只勾稽的出厂进度
 */
public record TruckProgressView(
        String batchNo,
        String batchStatus,
        boolean enabled,
        List<String> qualified,
        List<String> shipped,
        List<String> unshipped,
        List<ManifestView> manifests,
        TruckManifestResponse.TruckProgress progress) {

    /**
     * 单车冻结清单视图。
     *
     * @param truckNo      合成车号
     * @param version      车单当前版本
     * @param status       车单状态：RESERVED/RELEASED/REVOKED
     * @param frozenHeads  冻结头只标识集合（创建时给定顺序，终态不可改）
     * @param createdAt    清单创建冻结时刻（UTC ISO-8601）
     * @param revokedAt    整体撤销时刻，NULL 表示未撤销
     * @param dispatchedAt 发车核销时刻，NULL 表示未发车
     */
    public record ManifestView(
            String truckNo,
            int version,
            String status,
            List<String> frozenHeads,
            String createdAt,
            String revokedAt,
            String dispatchedAt) {
    }
}
