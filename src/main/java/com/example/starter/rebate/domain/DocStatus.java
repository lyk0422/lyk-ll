package com.example.starter.rebate.domain;

/**
 * 单证状态：COLLECTED 已采集可配单；RELEASED 撤批后释放；LATE 结案后迟到（仅登记事件）。
 */
public enum DocStatus {
    COLLECTED,
    RELEASED,
    LATE
}
