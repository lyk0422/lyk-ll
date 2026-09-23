package com.example.starter.slam.service;

import com.example.starter.slam.api.OptimizeRequest;
import com.example.starter.slam.api.ResultView;
import com.example.starter.slam.api.TaskView;

/**
 * 优化任务服务：提交（含合并）、查询、重试与结果版本保护。
 */
public interface OptimizationService {

    TaskView submit(OptimizeRequest request);

    TaskView getTask(String taskId);

    TaskView getByRequestId(String requestId);

    /**
     * 对可重试失败任务按原快照发起重试；非失败态或不存在按契约抛出异常。
     */
    TaskView retry(String taskId);

    ResultView getResult(String taskId);
}
