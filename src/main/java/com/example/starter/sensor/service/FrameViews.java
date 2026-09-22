package com.example.starter.sensor.service;

import com.example.starter.sensor.api.FrameInputView;
import com.example.starter.sensor.api.FrameView;
import com.example.starter.sensor.po.FrameInputRow;
import com.example.starter.sensor.po.FrameRow;

import java.util.Arrays;
import java.util.List;

/**
 * 融合帧持久化行到 API 视图的转换。
 */
final class FrameViews {

    private FrameViews() {
    }

    static FrameView toView(FrameRow frame, List<FrameInputRow> inputs, boolean reconstructed) {
        return new FrameView(
                frame.frameId(),
                frame.targetTs(),
                frame.status(),
                frame.committedAt(),
                inputs.stream().map(FrameViews::toInputView).toList(),
                frame.reason(),
                frame.reasonDetail(),
                reconstructed);
    }

    static FrameInputView toInputView(FrameInputRow row) {
        List<String> anchors = row.anchorMessageIds() == null || row.anchorMessageIds().isBlank()
                ? List.of()
                : Arrays.stream(row.anchorMessageIds().split(","))
                        .filter(s -> !s.isBlank())
                        .toList();
        return new FrameInputView(
                row.sensor(),
                row.messageId(),
                row.rawTs(),
                row.correctedTs(),
                row.timeDeltaNanos(),
                row.interpolated(),
                anchors);
    }
}
