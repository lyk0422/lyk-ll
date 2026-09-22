package com.example.starter.api.dto;

import java.util.List;

/**
 * 帧解析结果。
 *
 * @param accepted     true 表示帧有效且（在为最新帧时）更新了信号状态
 * @param replayed     true 表示相同帧（版本+CAN ID+序号+时戳+负载）重放，未重复产生状态变更事件
 * @param stale        true 表示旧时戳/较低序号帧，仅留存审计，未覆盖状态
 * @param rejectReason 拒绝原因：BAD_LENGTH/UNKNOWN_ID/MUX_NOT_MATCH；接受时为 null
 * @param canId        CAN ID
 * @param frameSeq     帧序号
 * @param timestampNs  帧纳秒时戳
 * @param signals      本帧解码出的信号值（被拒绝时为空）
 */
public record FrameParseResponse(
        boolean accepted,
        boolean replayed,
        boolean stale,
        String rejectReason,
        long canId,
        long frameSeq,
        long timestampNs,
        List<DecodedSignal> signals) {

    /**
     * 单个信号的解码值。
     *
     * @param name         信号名称
     * @param rawValue     原始值
     * @param physicalValue 物理值（原始值 * factor + offset）
     * @param unit         单位
     * @param enumText     枚举文本，无映射时为 null
     */
    public record DecodedSignal(
            String name,
            long rawValue,
            double physicalValue,
            String unit,
            String enumText) {
    }
}
