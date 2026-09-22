package com.example.starter.dbc;

import java.util.List;

/**
 * 帧解码失败原因。
 */
public enum FrameRejectReason {
    INVALID_LENGTH,
    UNKNOWN_ID,
    MUX_NOT_MATCHED
}
