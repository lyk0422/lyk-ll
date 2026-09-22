package com.example.starter.tf.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 写入静态/动态变换的公共载荷。
 * 静态变换忽略 {@code stampNanos}；动态变换必须提供 epoch 纳秒时间戳。
 *
 * @param parentFrame 父坐标系
 * @param childFrame  子坐标系
 * @param stampNanos  动态样本时间戳，epoch 纳秒；静态为 null
 * @param source      变换来源标识，参与动态样本幂等键
 * @param pose        平移（米）与单位四元数
 */
public record TransformWriteRequest(@NotBlank String parentFrame,
                                    @NotBlank String childFrame,
                                    Long stampNanos,
                                    @NotBlank String source,
                                    @NotNull @Valid PoseDto pose) {
}
