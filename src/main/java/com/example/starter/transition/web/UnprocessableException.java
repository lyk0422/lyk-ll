package com.example.starter.transition.web;

/**
 * 业务规则未满足时抛出，映射为 HTTP 422（如工艺窗口越界、复议资格不符）。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
