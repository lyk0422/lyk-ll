package com.example.starter.ledger.domain;

/**
 * 电池资产流转事件类型，按事件链顺序追加。
 */
public enum EventType {
    /** 建档（事件链起点）。 */
    REGISTER,
    /** 回站入站。 */
    INBOUND,
    /** 检测（结论不可修改）。 */
    INSPECTION,
    /** 检测合格后上架可换。 */
    LISTING,
    /** 换出并绑定车辆。 */
    SWAP_OUT,
    /** 站间调拨调出，进入在途。 */
    TRANSFER_DISPATCH,
    /** 调入方到站签收。 */
    TRANSFER_RECEIVE,
    /** 在途超时，调拨异常。 */
    TRANSFER_EXCEPTION,
    /** 人工核销。 */
    TRANSFER_WRITEOFF
}
