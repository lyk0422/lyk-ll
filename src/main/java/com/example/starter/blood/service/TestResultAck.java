package com.example.starter.blood.service;

import com.example.starter.blood.domain.UnitStatus;

/**
 * 检测结果回传应答。
 *
 * @param barcode 条码
 * @param status  回传处理后的单位状态
 * @param late    是否为终态后的迟到回传（仅记录事件，不改变状态）
 */
public record TestResultAck(String barcode, UnitStatus status, boolean late) {
}
