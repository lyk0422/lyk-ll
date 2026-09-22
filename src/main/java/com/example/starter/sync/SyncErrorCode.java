package com.example.starter.sync;

/**
 * 业务稳定错误码。缺帧、超出窗口、无法插值、重复提交等均以此枚举对外暴露。
 */
public enum SyncErrorCode {
    /** 目标时刻已存在已提交融合帧。 */
    FRAME_ALREADY_COMMITTED,
    /** 指定时刻不存在已提交融合帧。 */
    FRAME_NOT_FOUND,
    /** 必需传感器没有任何样本，缺帧。 */
    MISSING_INPUT,
    /** 传感器有样本但全部超出配对窗口，且缺少另一侧样本无法插值。 */
    OUT_OF_WINDOW,
    /** 窗口内无样本，两侧样本存在但跨度超出插值上限。 */
    CANNOT_INTERPOLATE,
    /** 漂移参数版本不合法（必须大于当前版本）。 */
    INVALID_DRIFT_VERSION,
    /** 请求参数不合法。 */
    BAD_REQUEST
}
