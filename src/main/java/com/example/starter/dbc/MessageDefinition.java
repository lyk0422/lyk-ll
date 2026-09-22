package com.example.starter.dbc;

import java.util.List;

/**
 * 一个 CAN 报文（同一 CAN ID）在某个 DBC 版本中的信号定义集合。
 *
 * @param canId    CAN 标识符
 * @param name     报文名
 * @param dlc      固定负载长度（字节）
 * @param signals  信号定义列表（含多路选择信号与被复用信号）
 */
public record MessageDefinition(
        long canId,
        String name,
        int dlc,
        List<SignalDefinition> signals
) {
}
