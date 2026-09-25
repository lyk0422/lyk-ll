package com.example.rebate.web;

/**
 * 409 冲突：幂等指纹不一致、单证被其它有效批次占用、状态流转冲突等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(409, message);
    }
}
