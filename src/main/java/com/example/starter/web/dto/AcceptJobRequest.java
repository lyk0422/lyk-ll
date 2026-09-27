package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 列车接入请求。
 *
 * @param requestId          幂等请求键
 * @param trainNo            车次（合成数据）
 * @param track              股道号
 * @param cars               编组车辆清单
 * @param tailControlEnabled 是否启用列尾装置联锁；缺省 false（历史作业不增加新门禁）
 */
public record AcceptJobRequest(
        @NotBlank String requestId,
        @NotBlank String trainNo,
        @NotBlank String track,
        @NotEmpty List<@Valid CarDto> cars,
        Boolean tailControlEnabled) {

    /**
     * 兼容历史调用（未启用列尾控制）。
     */
    public AcceptJobRequest(String requestId, String trainNo, String track, List<CarDto> cars) {
        this(requestId, trainNo, track, cars, null);
    }

    /**
     * 是否启用列尾装置联锁（受控作业）。
     */
    public boolean tailControl() {
        return Boolean.TRUE.equals(tailControlEnabled);
    }
}
