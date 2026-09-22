package com.example.starter.tf2.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 写入一条变换（静态/动态样本）的请求体。
 *
 * @param parentFrame 父坐标系
 * @param childFrame  子坐标系
 * @param dynamic     true=动态样本（stampNanos 必填）；false=静态变换
 * @param stampNanos  动态样本时间戳（纳秒）；静态请求可空
 * @param tx,ty,tz    平移分量（米）
 * @param qx,qy,qz,qw 旋转四元数分量
 * @param source      来源标识（幂等判定用）
 */
public record TransformRequest(
        @NotBlank String parentFrame,
        @NotBlank String childFrame,
        @NotNull Boolean dynamic,
        Long stampNanos,
        double tx,
        double ty,
        double tz,
        double qx,
        double qy,
        double qz,
        @NotNull Double qw,
        @NotBlank String source
) {
}
