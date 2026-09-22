package com.example.starter.sensor.domain;

/**
 * 融合帧构建/输入配对失败的稳定原因码。
 */
public enum FrameFailReason {
    /** 水位线尚未越过窗口右缘，数据可能不完整，允许稍后重试。 */
    WAITING_FOR_WATERMARK,
    /** 该传感器尚无可用于窗口的数据（缺帧）。 */
    MISSING_FRAME,
    /** 存在数据但没有任何消息落入配对窗口（超出窗口）。 */
    OUT_OF_WINDOW,
    /** 需要插值但前后帧间距过大或缺少单侧支撑（无法插值）。 */
    CANNOT_INTERPOLATE
}
