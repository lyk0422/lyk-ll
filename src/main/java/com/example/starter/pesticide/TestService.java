package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.RecheckRequest;
import com.example.starter.pesticide.dto.ApiDtos.RecheckResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 快检与定量复检服务：抑制率达到 50 判疑似（恰 50 判疑似）；
 * 疑似冻结准入，复检合格放行、不合格转监督处置并留样 48 小时；
 * 终态后迟到结果只记迟到事件。
 */
@Service
public class TestService {

    /** 疑似超标抑制率阈值（单位：%），达到即判疑似 */
    public static final BigDecimal SUSPECT_THRESHOLD = new BigDecimal("50");
    /** 处置结案留样时长（单位：小时） */
    public static final int RETENTION_HOURS = 48;

    private final BatchDao batchDao;
    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final JointDao jointDao;
    private final AlertService alertService;
    private final Clock clock;

    public TestService(BatchDao batchDao, TestDao testDao, LedgerDao ledgerDao,
                       JointDao jointDao, AlertService alertService, Clock clock) {
        this.batchDao = batchDao;
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.jointDao = jointDao;
        this.alertService = alertService;
        this.clock = clock;
    }

    /**
     * 快检：requestId 幂等；批次+项目去重，重复送检 409；终态批次只记迟到事件。
     */
    @Transactional
    public TestResponse rapidTest(TestRequest request) {
        String fingerprint = Fingerprint.of(request.requestId(), String.valueOf(request.batchId()),
                request.item(), request.inhibitionRate().toPlainString());
        var existing = testDao.findTestByRequestId(request.requestId());
        if (existing.isPresent()) {
            TestDao.TestRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            BatchDao.BatchRow batch = batchDao.findById(row.batchId()).orElseThrow();
            return toTestResponse(row, batch.status(), true, false);
        }
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        LocalDateTime now = nowUtc();
        if (status.isTerminal()) {
            ledgerDao.insertLateEvent(batch.id(), "LATE_TEST",
                    "item=" + request.item() + ", inhibitionRate=" + request.inhibitionRate(), now);
            return new TestResponse(null, request.requestId(), batch.id(), request.item(),
                    request.inhibitionRate(), null, batch.status(),
                    now.toInstant(ZoneOffset.UTC), false, true);
        }
        if (status == BatchStatus.FROZEN) {
            throw ApiException.conflict("批次疑似冻结待复检，同一批次同时刻只允许一条检测链");
        }
        if (!batchDao.sampleExists(batch.id(), request.item())) {
            throw ApiException.conflict("批次+项目未抽样，不得送检");
        }
        if (testDao.testExists(batch.id(), request.item())) {
            throw ApiException.conflict("批次+项目已检测，拒绝重复送检");
        }
        String result = request.inhibitionRate().compareTo(SUSPECT_THRESHOLD) >= 0
                ? "SUSPECT" : "NORMAL";
        long testId;
        try {
            testId = testDao.insertTest(request.requestId(), fingerprint, batch.id(), request.item(),
                    request.inhibitionRate(), result, now);
        } catch (DuplicateKeyException e) {
            var raced = testDao.findTestByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                BatchDao.BatchRow current = batchDao.findById(batch.id()).orElseThrow();
                return toTestResponse(raced.get(), current.status(), true, false);
            }
            throw ApiException.conflict("批次+项目已检测，拒绝重复送检");
        }
        String batchStatus = batch.status();
        if ("SUSPECT".equals(result)) {
            batchDao.updateStatus(batch.id(), BatchStatus.FROZEN.name(), now);
            batchStatus = BatchStatus.FROZEN.name();
            alertService.maybeTrigger(batch.origin(), batch.variety(), now);
        }
        return new TestResponse(testId, request.requestId(), batch.id(), request.item(),
                request.inhibitionRate(), result, batchStatus, now.toInstant(ZoneOffset.UTC),
                false, false);
    }

    /**
     * 定量复检：requestId 幂等；仅疑似冻结批次可复检；
     * 合格放行、不合格转监督处置并留样 48 小时（同一事务）；终态批次只记迟到事件。
     */
    @Transactional
    public RecheckResponse recheck(RecheckRequest request) {
        String fingerprint = Fingerprint.of(request.requestId(), String.valueOf(request.batchId()),
                request.item(), String.valueOf(request.qualified()));
        var existing = testDao.findRecheckByRequestId(request.requestId());
        if (existing.isPresent()) {
            TestDao.RecheckRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            BatchDao.BatchRow batch = batchDao.findById(row.batchId()).orElseThrow();
            return toRecheckResponse(row, batch, true, false);
        }
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        LocalDateTime now = nowUtc();
        if (status.isTerminal()) {
            ledgerDao.insertLateEvent(batch.id(), "LATE_RECHECK",
                    "item=" + request.item() + ", qualified=" + request.qualified(), now);
            return new RecheckResponse(null, request.requestId(), batch.id(), request.item(),
                    request.qualified(), batch.status(), null, now.toInstant(ZoneOffset.UTC),
                    false, true);
        }
        if (status != BatchStatus.FROZEN) {
            throw ApiException.conflict("批次非疑似冻结状态，无需复检: " + batch.status());
        }
        if (jointDao.existsUndecidedJointByBatch(batch.id())) {
            throw ApiException.conflict("批次已启用定量联检，须完成联检综合判定，不得沿用单项目复检放行");
        }
        long recheckId;
        try {
            recheckId = testDao.insertRecheck(request.requestId(), fingerprint, batch.id(),
                    request.item(), request.qualified(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次+项目已复检，拒绝重复复检");
        }
        String batchStatus;
        Instant retentionUntil = null;
        if (request.qualified()) {
            ledgerDao.insertAdmission(batch.id(), now);
            batchDao.updateStatus(batch.id(), BatchStatus.RELEASED.name(), now);
            batchStatus = BatchStatus.RELEASED.name();
        } else {
            ledgerDao.insertDisposal(batch.id(), "定量复检不合格，转监督处置", now);
            LocalDateTime until = now.plusHours(RETENTION_HOURS);
            ledgerDao.insertRetention(batch.id(), RETENTION_HOURS, until, now);
            batchDao.updateStatus(batch.id(), BatchStatus.DISPOSED.name(), now);
            batchStatus = BatchStatus.DISPOSED.name();
            retentionUntil = until.toInstant(ZoneOffset.UTC);
        }
        return new RecheckResponse(recheckId, request.requestId(), batch.id(), request.item(),
                request.qualified(), batchStatus, retentionUntil, now.toInstant(ZoneOffset.UTC),
                false, false);
    }

    private TestResponse toTestResponse(TestDao.TestRow row, String batchStatus,
                                        boolean replayed, boolean late) {
        return new TestResponse(row.id(), row.requestId(), row.batchId(), row.item(),
                row.inhibitionRate(), row.result(), batchStatus,
                row.createdAt().toInstant(ZoneOffset.UTC), replayed, late);
    }

    private RecheckResponse toRecheckResponse(TestDao.RecheckRow row, BatchDao.BatchRow batch,
                                              boolean replayed, boolean late) {
        Instant retentionUntil = ledgerDao.findRetention(row.batchId())
                .map(r -> r.retainUntil().toInstant(ZoneOffset.UTC))
                .orElse(null);
        return new RecheckResponse(row.id(), row.requestId(), row.batchId(), row.item(),
                row.qualified(), batch.status(), retentionUntil,
                row.createdAt().toInstant(ZoneOffset.UTC), replayed, late);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
