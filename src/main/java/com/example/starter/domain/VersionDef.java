package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 加载到内存的完整版本定义（用于解码）。
 */
public record VersionDef(
        DbcVersion version,
        List<MessageDef> messages) {

    /**
     * 报文定义及其信号列表。
     */
    public record MessageDef(
            long id,
            long canId,
            String name,
            int dlc,
            List<SignalDef> signals) {
    }

    /**
     * 信号定义（含枚举映射）。
     */
    public record SignalDef(
            long id,
            String name,
            ByteOrder byteOrder,
            int startBit,
            int bitLength,
            boolean signed,
            double factor,
            double offset,
            String unit,
            MultiplexRole multiplexRole,
            Long multiplexValue,
            Map<Long, String> valueTable) {
    }
}
