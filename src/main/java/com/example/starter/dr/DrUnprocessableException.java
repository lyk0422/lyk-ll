package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 语义性失败：冻结时存在缺值（PENDING）等无法处理的实体状态（422）。
 */
public class DrUnprocessableException extends DrBusinessException {

    public DrUnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
