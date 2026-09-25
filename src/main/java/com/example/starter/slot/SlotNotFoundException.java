package com.example.starter.slot;

/**
 * 系列不存在异常：退回或查询时找不到对应 requestId。
 */
public class SlotNotFoundException extends RuntimeException {

    public SlotNotFoundException(String message) {
        super(message);
    }
}
