package com.example.starter.dbc;

/**
 * DBC 定义本身非法（位越界、重复信号名等），导入整体回滚。映射为 HTTP 422。
 */
public class InvalidDefinitionException extends RuntimeException {
    public InvalidDefinitionException(String message) {
        super(message);
    }
}
