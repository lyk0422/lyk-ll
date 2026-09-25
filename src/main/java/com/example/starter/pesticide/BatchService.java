package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.BatchDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.LateEventView;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportResponse;
import com.example.starter.pesticide.dto.ApiDtos.SampleResponse;
import com.example.starter.pesticide.dto.ApiDtos.ReleaseResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 批次服务：产地报备、抽样、判定放行（判定与准入同事务）。
 */
@Service
public class BatchService {

    private final BatchDao batchDao;
    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final Clock clock;

    public BatchService(BatchDao batchDao, TestDao testDao, LedgerDao ledgerDao, Clock clock) {
        this.batchDao = batchDao;
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.clock = clock;
    }

    /**
     * 产地报备：requestId 幂等，同键同参返回首次结果，同键异参 409；
     * 产地+品种+批次号唯一，并发报备仅一条受理；预警期间报备的批次加严抽检。
     */
    @Transactional
    public ReportResponse report(ReportRequest request) {
        String fingerprint = Fingerprint.of(request.requestId(), request.origin(), request.variety(),
                request.batchNo(), request.quantity().toPlainString());
        var existing = batchDao.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            BatchDao.BatchRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toReportResponse(row, true);
        }
        String samplingLevel = ledgerDao.hasActiveAlert(request.origin(), request.variety())
                ? "STRICT" : "NORMAL";
        LocalDateTime now = nowUtc();
        try {
            long id = batchDao.insertBatch(request.requestId(), fingerprint, request.origin(),
                    request.variety(), request.batchNo(), request.quantity(),
                    BatchStatus.REPORTED.name(), samplingLevel, now);
            BatchDao.BatchRow row = batchDao.findById(id).orElseThrow();
            return toReportResponse(row, false);
        } catch (DuplicateKeyException e) {
            // 并发报备：requestId 或业务唯一键冲突，仅一条受理
            var raced = batchDao.findByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toReportResponse(raced.get(), true);
            }
            throw ApiException.conflict("同产地同品种批次号已报备，或 requestId 冲突");
        }
    }

    /**
     * 抽样：按批次+项目去重，重复抽样 409；首次抽样将批次置为 SAMPLED。
     */
    @Transactional
    public SampleResponse sample(long batchId, String item) {
        BatchDao.BatchRow batch = batchDao.lockById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status.isTerminal() || status == BatchStatus.FROZEN) {
            throw ApiException.conflict("批次状态不允许抽样: " + batch.status());
        }
        if (batchDao.sampleExists(batchId, item)) {
            throw ApiException.conflict("批次+项目已抽样，拒绝重复抽样");
        }
        LocalDateTime now = nowUtc();
        long sampleId;
        try {
            sampleId = batchDao.insertSample(batchId, item, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次+项目已抽样，拒绝重复抽样");
        }
        if (status == BatchStatus.REPORTED) {
            batchDao.updateStatus(batchId, BatchStatus.SAMPLED.name(), now);
        }
        return new SampleResponse(sampleId, batchId, item, now.toInstant(ZoneOffset.UTC));
    }

    /**
     * 判定与准入放行：同一事务内完成，未判定（无快检记录）不得放行；
     * 疑似冻结批次不得放行；重复放行返回幂等成功。
     */
    @Transactional
    public ReleaseResponse release(long batchId) {
        BatchDao.BatchRow batch = batchDao.lockById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status == BatchStatus.RELEASED) {
            var admission = ledgerDao.findAdmission(batchId).orElseThrow();
            return new ReleaseResponse(batchId, batch.status(), admission.id(),
                    admission.releasedAt().toInstant(ZoneOffset.UTC), true);
        }
        if (status == BatchStatus.DISPOSED) {
            throw ApiException.conflict("批次已处置结案，不得放行");
        }
        if (status == BatchStatus.FROZEN) {
            throw ApiException.conflict("批次疑似超标冻结中，待定量复检，不得放行");
        }
        List<TestDao.TestRow> tests = testDao.findTestsByBatch(batchId);
        if (tests.isEmpty()) {
            throw ApiException.conflict("批次未判定（无快检记录），不得放行");
        }
        boolean anySuspect = tests.stream().anyMatch(t -> "SUSPECT".equals(t.result()));
        if (anySuspect) {
            throw ApiException.conflict("批次存在疑似超标项目，不得放行");
        }
        LocalDateTime now = nowUtc();
        long admissionId = ledgerDao.insertAdmission(batchId, now);
        batchDao.updateStatus(batchId, BatchStatus.RELEASED.name(), now);
        return new ReleaseResponse(batchId, BatchStatus.RELEASED.name(), admissionId,
                now.toInstant(ZoneOffset.UTC), false);
    }

    /**
     * 批次详情：含快检记录、迟到事件与留样截止时间。
     */
    @Transactional(readOnly = true)
    public BatchDetailResponse detail(long batchId) {
        BatchDao.BatchRow batch = batchDao.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        List<TestView> tests = testDao.findTestsByBatch(batchId).stream()
                .map(t -> new TestView(t.id(), t.item(), t.inhibitionRate(), t.result(),
                        t.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        List<LateEventView> lateEvents = ledgerDao.findLateEvents(batchId).stream()
                .map(e -> new LateEventView(e.id(), e.eventType(), e.payload(),
                        e.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        var retentionUntil = ledgerDao.findRetention(batchId)
                .map(r -> r.retainUntil().toInstant(ZoneOffset.UTC))
                .orElse(null);
        return new BatchDetailResponse(batch.id(), batch.origin(), batch.variety(), batch.batchNo(),
                batch.quantity(), batch.status(), batch.samplingLevel(),
                batch.createdAt().toInstant(ZoneOffset.UTC), tests, lateEvents, retentionUntil);
    }

    private ReportResponse toReportResponse(BatchDao.BatchRow row, boolean replayed) {
        return new ReportResponse(row.id(), row.requestId(), row.origin(), row.variety(),
                row.batchNo(), row.quantity(), row.status(), row.samplingLevel(),
                row.createdAt().toInstant(ZoneOffset.UTC), replayed);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
