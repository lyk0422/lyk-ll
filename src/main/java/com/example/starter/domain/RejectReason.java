package com.example.starter.domain;

/**
 * 帧解析可区分的拒绝原因。
 */
public enum RejectReason {
    /** 负载长度与报文定义 DLC 不一致。 */
    BAD_LENGTH,
    /** 当前 DBC 版本中不存在该 CAN ID 的报文定义。 */
    UNKNOWN_ID,
    /** 报文存在被选择信号，但选择器原始值未匹配任何分支。 */
    MUX_NOT_MATCH,
    /** 旧时戳或较低序号帧，不得覆盖最新状态。 */
    STALE_FRAME
}
