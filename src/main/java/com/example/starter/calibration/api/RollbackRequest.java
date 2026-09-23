package com.example.starter.calibration.api;

/**
 * 回滚请求：按发布单号回滚该次灰度涉及的车辆。
 */
public record RollbackRequest(
        String releaseId,
        String operator,
        String remark
) {
}
