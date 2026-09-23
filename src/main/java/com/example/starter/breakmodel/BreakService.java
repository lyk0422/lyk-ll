package com.example.starter.breakmodel;

import com.example.starter.common.ApiException;
import com.example.starter.playout.PlayoutEngine;
import com.example.starter.state.ChannelStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 直播插播：只有更高优先级可压过当前素材或较低插播；同优先级按 requestId 字典序定唯一胜者。
 * (channelId, requestId) 为幂等键：同键同参返回首次冻结结果，同键异参返回 409。
 * 结束插播后恢复该时刻播单应播素材，不补播被覆盖部分。
 */
@Service
public class BreakService {

    /**
     * 插播开始结果（冻结存储，幂等重放原样返回）。
     */
    public record BreakStartResponse(String channelId, String requestId, int priority,
                                     long startMs, String status, long asRunSeq) {
    }

    /**
     * 插播结束结果（冻结存储）。resumedSeq 为恢复排播的 as-run 序号，空档时为空。
     */
    public record BreakEndResponse(String channelId, String requestId, long endMs,
                                   String status, Long resumedSeq) {
    }

    private final BreakRequestRepository breakRequestRepository;
    private final ChannelStateRepository channelStateRepository;
    private final PlayoutEngine engine;
    private final ObjectMapper objectMapper;

    public BreakService(BreakRequestRepository breakRequestRepository,
                        ChannelStateRepository channelStateRepository,
                        PlayoutEngine engine,
                        ObjectMapper objectMapper) {
        this.breakRequestRepository = breakRequestRepository;
        this.channelStateRepository = channelStateRepository;
        this.engine = engine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BreakStartResponse start(BreakStartRequest request) {
        String channelId = request.channelId();
        ChannelStateRepository.ChannelState state = channelStateRepository.lockByChannelId(channelId);
        String fingerprint = fingerprint(request);

        BreakRequestRepository.BreakRequestRow existing =
                breakRequestRepository.find(channelId, request.requestId());
        if (existing != null) {
            if (existing.fingerprint().equals(fingerprint)) {
                return readFrozen(existing.startResponse(), BreakStartResponse.class);
            }
            throw ApiException.conflict("requestId " + request.requestId() + " 已存在且参数不一致");
        }
        if (request.startMs() < state.watermarkMs()) {
            throw ApiException.conflict("插播开始时间早于结算水位线 " + state.watermarkMs());
        }

        BreakRequestRepository.BreakRequestRow active = breakRequestRepository.findActive(channelId);
        if (active != null) {
            boolean preempts = request.priority() > active.priority()
                    || (request.priority() == active.priority()
                        && request.requestId().compareTo(active.requestId()) < 0);
            if (!preempts) {
                throw ApiException.conflict("优先级不足：当前插播 " + active.requestId()
                        + " 优先级 " + active.priority());
            }
            breakRequestRepository.markEnded(channelId, active.requestId(), request.startMs(),
                    toJson(new BreakEndResponse(channelId, active.requestId(),
                            request.startMs(), "PREEMPTED", null)));
        }

        engine.rewriteFrom(channelId, request.startMs());
        PlayoutEngine.DesiredMedia media = new PlayoutEngine.DesiredMedia(
                "BREAK", null, "LIVE:" + request.requestId(), request.requestId());
        engine.openRecord(channelId, state.nextSeq(), media, request.startMs());
        channelStateRepository.advanceSeq(channelId, state.nextSeq() + 1);

        BreakStartResponse response = new BreakStartResponse(channelId, request.requestId(),
                request.priority(), request.startMs(), "ACTIVE", state.nextSeq());
        breakRequestRepository.insert(new BreakRequestRepository.BreakRequestRow(
                channelId, request.requestId(), request.priority(), request.startMs(), null,
                "ACTIVE", fingerprint, toJson(response), null));
        return response;
    }

    @Transactional
    public BreakEndResponse end(BreakEndRequest request) {
        String channelId = request.channelId();
        ChannelStateRepository.ChannelState state = channelStateRepository.lockByChannelId(channelId);
        BreakRequestRepository.BreakRequestRow row =
                breakRequestRepository.find(channelId, request.requestId());
        if (row == null) {
            throw ApiException.notFound("插播请求不存在: " + request.requestId());
        }
        if ("ENDED".equals(row.status())) {
            if (row.endMs() != null && row.endMs().longValue() == request.endMs()) {
                return readFrozen(row.endResponse(), BreakEndResponse.class);
            }
            throw ApiException.conflict("插播 " + request.requestId() + " 已结束且结束时间不一致");
        }
        if (request.endMs() < row.startMs()) {
            throw ApiException.unprocessable("插播结束时间早于开始时间");
        }
        if (request.endMs() < state.watermarkMs()) {
            throw ApiException.conflict("插播结束时间早于结算水位线 " + state.watermarkMs());
        }

        engine.rewriteFrom(channelId, request.endMs());
        PlayoutEngine.DesiredMedia scheduled = engine.resolveScheduled(state, channelId, request.endMs());
        long nextSeq = state.nextSeq();
        Long resumedSeq = null;
        if (scheduled != null) {
            engine.openRecord(channelId, nextSeq, scheduled, request.endMs());
            resumedSeq = nextSeq;
            nextSeq++;
        }
        channelStateRepository.advanceSeq(channelId, nextSeq);

        BreakEndResponse response = new BreakEndResponse(channelId, request.requestId(),
                request.endMs(), "ENDED", resumedSeq);
        breakRequestRepository.markEnded(channelId, request.requestId(), request.endMs(), toJson(response));
        return response;
    }

    private String fingerprint(BreakStartRequest request) {
        return request.priority() + "|" + request.startMs() + "|" + request.mediaDigest();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化冻结响应失败", e);
        }
    }

    private <T> T readFrozen(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("读取冻结响应失败", e);
        }
    }
}
