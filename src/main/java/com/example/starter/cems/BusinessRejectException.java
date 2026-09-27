package com.example.starter.cems;

/**
 * 业务规则拒绝（HTTP 422）：参数本身合法、对象存在，但当前业务状态不允许该操作，
 * 例如溢流日结时仍存在缺雨量待核小时、溢流许可版本窗口与既有版本重叠。
 */
public class BusinessRejectException extends RuntimeException {

    public BusinessRejectException(String message) {
        super(message);
    }
}
