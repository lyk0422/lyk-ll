package com.example.starter.hazmat.web;

/**
 * 通用操作结果视图。
 */
public record ResultView(boolean success, String message) {

    public static ResultView ok(String message) {
        return new ResultView(true, message);
    }
}
