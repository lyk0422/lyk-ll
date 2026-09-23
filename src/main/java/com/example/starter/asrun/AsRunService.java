package com.example.starter.asrun;

import com.example.starter.ackmodel.AckRequest;
import com.example.starter.clock.SyntheticClock;
import com.example.starter.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * as-run 查询与适配器 ACK。ACK 幂等且可迟到：只确认对应 asRunSequence 的记录，
 * 不改写任何在播状态；被后续记录取代（SUPERSEDED）的记录仍可确认但不影响当前状态。
 */
@Service
public class AsRunService {

    private final AsRunRepository asRunRepository;
    private final SyntheticClock clock;

    public AsRunService(AsRunRepository asRunRepository, SyntheticClock clock) {
        this.asRunRepository = asRunRepository;
        this.clock = clock;
    }

    @Transactional
    public AsRunRecord ack(AckRequest request) {
        AsRunRecord record = asRunRepository.findBySeq(request.channelId(), request.asRunSequence());
        if (record == null) {
            throw ApiException.notFound("as-run 记录不存在: channel=" + request.channelId()
                    + " seq=" + request.asRunSequence());
        }
        if (!record.acked()) {
            long ackedAtMs = request.atMs() != null ? request.atMs() : clock.nowMs();
            asRunRepository.markAcked(request.channelId(), request.asRunSequence(), ackedAtMs);
            record = asRunRepository.findBySeq(request.channelId(), request.asRunSequence());
        }
        return record;
    }

    @Transactional(readOnly = true)
    public List<AsRunRecord> query(String channelId, Long fromMs, Long toMs) {
        if (fromMs != null && toMs != null && toMs < fromMs) {
            throw ApiException.unprocessable("查询窗口 to 早于 from");
        }
        return asRunRepository.query(channelId, fromMs, toMs);
    }
}
