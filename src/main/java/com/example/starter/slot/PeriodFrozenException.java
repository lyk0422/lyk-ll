package com.example.starter.slot;

/**
 * 本期已发布冻结异常：分配表发布后不再接受新的时刻申请。
 */
public class PeriodFrozenException extends RuntimeException {

    public PeriodFrozenException(String message) {
        super(message);
    }
}
