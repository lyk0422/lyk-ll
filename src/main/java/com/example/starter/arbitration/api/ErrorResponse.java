package com.example.starter.arbitration.api;

import java.util.List;

/**
 * 指令校验失败明细（HTTP 400 响应体）。
 *
 * @param error  错误类型标识
 * @param reasons 全部拒绝原因（位置越界、超速、超限、时钟回拨、过期等可区分）
 */
public record ErrorResponse(String error, List<String> reasons) {

    public static ErrorResponse of(String error, List<String> reasons) {
        return new ErrorResponse(error, reasons);
    }

    public static ErrorResponse single(String error, String reason) {
        return new ErrorResponse(error, List.of(reason));
    }
}
