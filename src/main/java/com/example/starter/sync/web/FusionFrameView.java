package com.example.starter.sync.web;

import java.util.List;

import com.example.starter.sync.domain.FrameInput;
import com.example.starter.sync.domain.FusionFrame;

/**
 * 融合帧视图：目标时刻、帧ID（重建时为空）及各传感器输入证据。
 */
public record FusionFrameView(
        Long id,
        long targetTs,
        List<InputView> inputs) {

    /**
     * 单个输入证据视图。
     */
    public record InputView(
            String sensorType,
            String messageId,
            long rawTs,
            long correctedTs,
            long deltaMs,
            boolean interpolated) {

        static InputView of(FrameInput input) {
            return new InputView(input.sensorType().name(), input.messageId(), input.rawTs(),
                    input.correctedTs(), input.deltaMs(), input.interpolated());
        }
    }

    /**
     * 从领域帧转换。
     */
    public static FusionFrameView of(FusionFrame frame) {
        return new FusionFrameView(frame.id(), frame.targetTs(),
                frame.inputs().stream().map(InputView::of).toList());
    }
}
