package com.example.starter.gas.web;

import org.springframework.http.HttpStatus;

/**
 * 同站同时刻已有执行中联动任务：并发下达时唯一受理，落选方返回 409。
 */
public class TaskSlotConflictException extends ApiException {

    public TaskSlotConflictException(String message) {
        super(HttpStatus.CONFLICT, "LINKAGE_TASK_CONFLICT", message);
    }
}
