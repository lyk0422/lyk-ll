package com.example.starter.sensor.service;

import com.example.starter.sensor.SyncProperties;
import com.example.starter.sensor.api.FrameInputView;
import com.example.starter.sensor.api.FrameView;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.engine.PairingEngine;
import com.example.starter.sensor.po.CorrectionRow;
import com.example.starter.sensor.po.MessageRow;
import com.example.starter.sensor.repo.SyncRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件重放/重建服务：仅以 H2 中的消息事件与校正版本为输入，纯函数式重建指定目标时刻的
 * 融合帧及输入证据。不写入任何数据；相同事件集合下重复重放结果完全一致（幂等）。
 *
 * <p>缺帧、超出窗口、无法插值分别返回 {@code MISSING_FRAME}/{@code OUT_OF_WINDOW}/
 * {@code CANNOT_INTERPOLATE} 稳定原因；重建不做水位线门控（事件集合即输入全集）。
 */
@Service
public class ReplayService {

    private final SyncRepository repository;
    private final SyncProperties properties;

    public ReplayService(SyncRepository repository, SyncProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 从事件记录重建融合帧。
     *
     * @param targetTimestamp    目标融合时刻，epoch 纳秒
     * @param correctionCutoffMs 仅使用该墙钟时刻（含）之前写入的校正版本；null 表示不限制。
     *                           传入历史帧的 correction_cutoff 可在后续热更新后复原历史结果。
     */
    public FrameView reconstruct(long targetTimestamp, Long correctionCutoffMs) {
        String frameId = SyncService.frameIdOf(targetTimestamp);
        List<FrameInputView> inputs = new ArrayList<>();

        for (SensorType sensor : SensorType.values()) {
            List<MessageRow> messages = repository.findMessages(sensor.name());
            List<CorrectionRow> corrections = correctionCutoffMs == null
                    ? repository.findCorrections(sensor.name())
                    : repository.findCorrectionsCreatedBefore(sensor.name(), correctionCutoffMs);

            PairingEngine.PairResult result = PairingEngine.pair(sensor, messages, corrections,
                    targetTimestamp, windowOf(sensor));
            if (!result.isSuccess()) {
                return new FrameView(frameId, targetTimestamp, "REJECTED", null,
                        List.of(), result.reason().name(), result.detail(), true);
            }
            PairingEngine.MatchedInput in = result.input();
            inputs.add(new FrameInputView(in.sensor().name(), in.messageId(), in.rawTs(),
                    in.correctedTs(), in.timeDeltaNanos(), in.interpolated(),
                    in.anchorMessageIds()));
        }

        return new FrameView(frameId, targetTimestamp, "COMMITTED", null,
                List.copyOf(inputs), null, null, true);
    }

    private SyncProperties.SensorWindow windowOf(SensorType sensor) {
        SyncProperties.SensorWindow window =
                properties.getWindows().get(sensor.name().toLowerCase());
        if (window == null) {
            throw new IllegalStateException("缺少传感器 " + sensor + " 的窗口配置 sensor.sync.windows."
                    + sensor.name().toLowerCase());
        }
        return window;
    }
}
