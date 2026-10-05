package com.example.starter.deicing.service;

/**
 * 除冰液可预占库存不足（含可用批次效期耗尽），申请须转人工确认队列。
 */
public class InsufficientFluidException extends RuntimeException {

    public InsufficientFluidException(String message) {
        super(message);
    }
}
