package com.example.starter.sync.domain;

import java.util.List;

/**
 * 不可变融合帧。targetTs 为目标融合时刻(纪元毫秒)，inputs 覆盖全部必需传感器。
 */
public record FusionFrame(
        Long id,
        long targetTs,
        List<FrameInput> inputs) {

    public FusionFrame {
        inputs = List.copyOf(inputs);
    }

    /**
     * 按传感器类型取输入证据，不存在时返回 null。
     */
    public FrameInput inputOf(com.example.starter.sync.SensorType type) {
        for (FrameInput input : inputs) {
            if (input.sensorType() == type) {
                return input;
            }
        }
        return null;
    }
}
