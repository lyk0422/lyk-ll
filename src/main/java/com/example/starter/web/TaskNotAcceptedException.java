package com.example.starter.web;

/**
 * 并发下达联动任务时未获唯一受理（同一门站同时刻仅允许一个执行中任务），映射 HTTP 409。
 */
public class TaskNotAcceptedException extends RuntimeException {

    public TaskNotAcceptedException(String message) {
        super(message);
    }
}
