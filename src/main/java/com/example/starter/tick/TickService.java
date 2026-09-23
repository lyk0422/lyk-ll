package com.example.starter.tick;

import com.example.starter.asrun.AsRunRecord;
import com.example.starter.asrun.AsRunRepository;
import com.example.starter.common.ApiException;
import com.example.starter.playout.PlayoutEngine;
import com.example.starter.state.ChannelStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度 tick：按绑定播单版本在指定时刻结算，生成单调 asRunSequence；
 * 同一时刻重复 tick 幂等，不重复生成记录；乱序或早于水位线的 tick 拒绝。
 */
@Service
public class TickService {

    /**
     * tick 结果：onAir 为结算后在播记录（可能为空），duplicate 表示幂等重放。
     */
    public record TickResponse(String channelId, long atMs, boolean duplicate,
                               Long generatedSeq, AsRunRecord onAir) {
    }

    /**
     * 水位线结果。
     */
    public record WatermarkResponse(String channelId, long watermarkMs) {
    }

    private final ChannelStateRepository channelStateRepository;
    private final AsRunRepository asRunRepository;
    private final PlayoutEngine engine;

    public TickService(ChannelStateRepository channelStateRepository,
                       AsRunRepository asRunRepository,
                       PlayoutEngine engine) {
        this.channelStateRepository = channelStateRepository;
        this.asRunRepository = asRunRepository;
        this.engine = engine;
    }

    @Transactional
    public TickResponse tick(TickRequest request) {
        String channelId = request.channelId();
        long at = request.atMs();
        ChannelStateRepository.ChannelState state = channelStateRepository.lockByChannelId(channelId);

        if (state.lastTickMs() != null && at == state.lastTickMs()) {
            return new TickResponse(channelId, at, true, null, asRunRepository.findOpen(channelId));
        }
        if (at < state.watermarkMs()) {
            throw ApiException.conflict("tick 时刻早于结算水位线 " + state.watermarkMs());
        }
        if (state.lastTickMs() != null && at < state.lastTickMs()) {
            throw ApiException.conflict("tick 时刻乱序：早于最近 tick " + state.lastTickMs());
        }

        PlayoutEngine.DesiredMedia desired = engine.resolveDesired(state, channelId, at);
        AsRunRecord current = asRunRepository.findOpen(channelId);
        long nextSeq = state.nextSeq();
        Long generatedSeq = null;

        if (desired == null || !desired.sameAs(current)) {
            if (current != null) {
                engine.closeRecord(channelId, current, at);
            }
            if (desired != null) {
                engine.openRecord(channelId, nextSeq, desired, at);
                generatedSeq = nextSeq;
                nextSeq++;
            }
        }
        channelStateRepository.advanceTick(channelId, at, nextSeq);
        return new TickResponse(channelId, at, false, generatedSeq, asRunRepository.findOpen(channelId));
    }

    /**
     * 推进结算水位线（只能单调前进）。结束时间不晚于水位线的记录此后不可修改。
     */
    @Transactional
    public WatermarkResponse advanceWatermark(String channelId, long watermarkMs) {
        ChannelStateRepository.ChannelState state = channelStateRepository.lockByChannelId(channelId);
        if (watermarkMs < state.watermarkMs()) {
            throw ApiException.conflict("水位线不可回退：当前 " + state.watermarkMs());
        }
        channelStateRepository.advanceWatermark(channelId, watermarkMs);
        return new WatermarkResponse(channelId, watermarkMs);
    }
}
