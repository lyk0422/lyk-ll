package com.example.starter.epidemic.api;

/**
 * 特殊订正双人确认的服务层命令（流程编号来自路径参数，两名审核员必须不同）。
 *
 * @param specialId 特殊订正流程编号
 * @param reviewer  审核员工号
 */
public record SpecialConfirmRequest(Long specialId, String reviewer) {
}
