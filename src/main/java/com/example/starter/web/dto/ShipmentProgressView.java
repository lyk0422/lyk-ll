package com.example.starter.web.dto;

import java.util.List;

/**
 * 分车出厂进度查询：给出每车冻结清单、已出厂/未出厂头只及批次进度，按头只勾稽而非按车计数。
 *
 * @param batchNo          屠宰批次号
 * @param status           批次当前状态
 * @param splitEnabled     是否已启用分车
 * @param qualifiedTotal   本批合格头只总数（取自原判定明细），即可分车出厂的头只全集
 * @param departedHeads    已出厂合格头只标识（跨已发车车辆汇总，字典序）
 * @param reservedHeads    已被未发车车单预占、尚未出厂的合格头只标识（字典序）
 * @param unshippedHeads   尚未出厂（含未预占与仅预占）的合格头只标识（字典序）
 * @param departedCount    已出厂合格头只数（按头只勾稽）
 * @param unshippedCount   未出厂合格头只数（按头只勾稽，不含病害处置头只）
 * @param allShipped       全部合格头只是否已恰核销完
 * @param vehicles         每车冻结清单视图，按车号排序
 */
public record ShipmentProgressView(
        String batchNo,
        String status,
        boolean splitEnabled,
        int qualifiedTotal,
        List<String> departedHeads,
        List<String> reservedHeads,
        List<String> unshippedHeads,
        int departedCount,
        int unshippedCount,
        boolean allShipped,
        List<VehicleView> vehicles) {
}
