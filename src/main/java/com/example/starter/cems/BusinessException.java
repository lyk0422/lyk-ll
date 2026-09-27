package com.example.starter.cems;

/**
 * 业务规则拒绝（HTTP 422）：参数合法、对象存在，但当前业务状态不允许该操作，
 * 例如溢流合规标记证据不足、存在待核小时时复核冻结等。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
