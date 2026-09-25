package com.example.starter.recheck;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.api.ConflictException;
import com.example.starter.api.Fingerprints;
import com.example.starter.api.NotFoundException;
import com.example.starter.batch.Batch;
import com.example.starter.batch.BatchRepository;
import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.RecheckResult;
import com.example.starter.event.LateEventRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定量复检服务：疑似冻结批次复检合格放行、不合格转监督处置并留样 48 小时；
 * 判定与放行/处置在同一事务内完成；结案后迟到结果只记迟到事件，重复结案幂等成功。
 */
@Service
public class RecheckService {

    /**
     * 留样时长：处置结案后 48 小时。
     */
    public static final long RETENTION_HOURS = 48;

    private final BatchRepository batchRepository;
    private final RecheckRepository recheckRepository;
    private final RetentionRepository retentionRepository;
    private final LateEventRepository lateEventRepository;
    private final Clock clock;

    public RecheckService(BatchRepository batchRepository, RecheckRepository recheckRepository,
                          RetentionRepository retentionRepository,
                          LateEventRepository lateEventRepository, Clock clock) {
        this.batchRepository = batchRepository;
        this.recheckRepository = recheckRepository;
        this.retentionRepository = retentionRepository;
        this.lateEventRepository = lateEventRepository;
        this.clock = clock;
    }

    /**
     * 提交定量复检结果。仅疑似冻结批次可复检；结案后新键结果记迟到事件并幂等成功。
     */
    @Transactional
    public RecheckResponse recheck(long batchId, RecheckRequest request) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchId));
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(batchId),
                request.result().name());
        var replay = recheckRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            if (!replay.get().fingerprint().equals(fingerprint)) {
                throw new ConflictException("同一 requestId 业务参数不一致，拒绝重放");
            }
            // 重复结案返回首次结果，幂等成功
            Retention retention = retentionRepository.findByBatchId(batchId).orElse(null);
            return RecheckResponse.from(replay.get(), batch.status(), retention);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (batch.status().isTerminal()) {
            lateEventRepository.insert(batchId, "RECHECK",
                    "结案后迟到复检结果，requestId=" + request.requestId(), now);
            return RecheckResponse.late(batchId, batch.status());
        }
        if (batch.status() != BatchStatus.SUSPECTED) {
            throw new ConflictException("批次非疑似冻结状态，不能复检");
        }
        long recheckId;
        try {
            recheckId = recheckRepository.insert(request.requestId(), fingerprint, batchId,
                    request.result(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("复检幂等键冲突");
        }
        if (request.result() == RecheckResult.PASS) {
            transition(batch, BatchStatus.RELEASED, now);
            return new RecheckResponse(recheckId, batchId, request.result(),
                    BatchStatus.RELEASED, null, false, now);
        }
        transition(batch, BatchStatus.DISPOSED, now);
        LocalDateTime retainUntil = now.plusHours(RETENTION_HOURS);
        retentionRepository.insert(batchId, retainUntil, now);
        return new RecheckResponse(recheckId, batchId, request.result(),
                BatchStatus.DISPOSED, retainUntil, false, now);
    }

    /**
     * 疑似冻结态到终态的条件流转，与复检记录、留样登记同事务。
     */
    private void transition(Batch batch, BatchStatus target, LocalDateTime now) {
        if (batchRepository.transitionStatus(batch.id(), BatchStatus.SUSPECTED, target, now) == 0) {
            throw new ConflictException("批次状态已变更，检测链冲突");
        }
    }
}
