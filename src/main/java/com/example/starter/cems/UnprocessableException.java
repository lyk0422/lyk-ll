package com.example.starter.cems;

/**
 * 业务拒绝（HTTP 422）：参数合法、对象存在，但当前业务状态不允许该操作，
 * 如日结快照已冻结后更正雨量、未冻结时独立调差、归档排口上的溢流操作等。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
