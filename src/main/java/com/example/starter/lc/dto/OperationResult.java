package com.example.starter.lc.dto;

/**
 * 业务操作结果：首次执行返回 Fresh（含响应体对象）；requestId 重放返回 Replay（含首次状态码与原始 JSON）。
 */
public sealed interface OperationResult permits OperationResult.Fresh, OperationResult.Replay {

    /** 首次执行结果（成功或确定性业务失败的响应体）。 */
    record Fresh(int httpStatus, Object body) implements OperationResult {
    }

    /** 同 requestId 同参重放：原样返回首次响应状态码与 JSON。 */
    record Replay(int httpStatus, String json) implements OperationResult {
    }
}
