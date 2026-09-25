package com.example.starter.museum.domain;

/**
 * 点交单类型：OUTBOUND 出库点交（申请→在展），RETURN 归还点交（在展→归还）。
 */
public enum HandoverType {
    OUTBOUND,
    RETURN
}
