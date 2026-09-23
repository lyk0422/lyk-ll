package com.example.starter.spectrum.service;

import com.example.starter.spectrum.api.ConflictResponse;
import com.example.starter.spectrum.api.LeaseCancelRequest;
import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.LeaseUpsertRequest;
import com.example.starter.spectrum.api.OccupancyItem;
import com.example.starter.spectrum.config.SpectrumClock;
import com.example.starter.spectrum.config.SpectrumProperties;
import com.example.starter.spectrum.domain.ConflictReport;
import com.example.starter.spectrum.domain.EventType;
import com.example.starter.spectrum.domain.LeaseHeader;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseSpec;
import com.example.starter.spectrum.domain.LeaseStatus;
import com.example.starter.spectrum.repo.LeaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 频谱租约协调服务。
 *
 * <p>并发模型：所有创建、修改、取消在单个事务内先对协调锁行加写锁，
 * 再读取当前活动租约集合做三维冲突判定并写入，从而得到可串行化的
 * 活动租约集合——不存在“分别检查后同时越过共享上限”的窗口。
 * 取消只改变未来有效性并保留全部历史版本；重复同参取消是无副作用成功。
 */
@Service
public class LeaseService {

    private final LeaseRepository repository;
    private final IdempotencyRecorder idempotencyRecorder;
    private final SpectrumClock clock;
    private final SpectrumProperties properties;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public LeaseService(LeaseRepository repository,
                        IdempotencyRecorder idempotencyRecorder,
                        SpectrumClock clock,
                        SpectrumProperties properties,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyRecorder = idempotencyRecorder;
        this.clock = clock;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 创建租约。 */
    public LeaseResponse create(LeaseUpsertRequest request) {
        LeaseSpec spec = toSpec(request);
        if (request.expectedVersion() != null && request.expectedVersion() != 0) {
            throw new InvalidLeaseRequestException(
                    "expectedVersion must be null or 0 for create");
        }
        String fingerprint = fingerprint("CREATE", null, 0, spec);
        long now = clock.nowMillis();
        TxOutcome outcome = txTemplate.execute(status -> {
            repository.lockCoordination();
            LeaseRepository.IdempotencyRow existing = repository.findIdempotency(request.requestId());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new IdempotencyConflictException(request.requestId());
                }
                return TxOutcome.replay(existing);
            }
            ConflictReport report = ConflictAnalyzer.analyze(spec, null, 0,
                    loadEffective(null), properties.sharedEirpLimitMw());
            if (!report.compatible()) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.CONFLICT.value(), "LEASE_CONFLICT", toJson(report), null, now);
                return TxOutcome.failure(HttpStatus.CONFLICT.value(), "LEASE_CONFLICT", report);
            }
            long leaseId = repository.insertHeader(now);
            repository.insertVersion(leaseId, 1, spec, request.requestId(), now);
            repository.insertEvent(leaseId, 1, request.requestId(), EventType.CREATED, now);
            idempotencyRecorder.record(request.requestId(), fingerprint,
                    HttpStatus.OK.value(), null, null, leaseId, now);
            LeaseResponse response = new LeaseResponse(leaseId, 1, LeaseStatus.ACTIVE,
                    spec.startTime(), spec.endTime(), spec.freqLowMhz(), spec.freqHighMhz(),
                    spec.mode(), spec.eirpMw(), spec.cells(), null, false);
            return TxOutcome.success(response);
        });
        return settle(outcome);
    }

    /** 修改租约（产生新版本，历史版本保留）。 */
    public LeaseResponse modify(long leaseId, LeaseUpsertRequest request) {
        LeaseSpec spec = toSpec(request);
        if (request.expectedVersion() == null) {
            throw new InvalidLeaseRequestException("expectedVersion is required for modify");
        }
        String fingerprint = fingerprint("MODIFY", leaseId, request.expectedVersion(), spec);
        long now = clock.nowMillis();
        TxOutcome outcome = txTemplate.execute(status -> {
            repository.lockCoordination();
            LeaseRepository.IdempotencyRow existing = repository.findIdempotency(request.requestId());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new IdempotencyConflictException(request.requestId());
                }
                return TxOutcome.replay(existing);
            }
            LeaseHeader header = repository.findHeader(leaseId);
            if (header == null) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.NOT_FOUND.value(), "LEASE_NOT_FOUND", null, null, now);
                return TxOutcome.failure(HttpStatus.NOT_FOUND.value(), "LEASE_NOT_FOUND", null);
            }
            if (header.status() == LeaseStatus.CANCELLED) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.CONFLICT.value(), "LEASE_TERMINAL", null, null, now);
                return TxOutcome.failure(HttpStatus.CONFLICT.value(), "LEASE_TERMINAL", null);
            }
            if (header.currentVersion() != request.expectedVersion()) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.CONFLICT.value(), "VERSION_CONFLICT", null, null, now);
                return TxOutcome.failure(HttpStatus.CONFLICT.value(), "VERSION_CONFLICT", null);
            }
            ConflictReport report = ConflictAnalyzer.analyze(spec, leaseId,
                    request.expectedVersion() + 1, loadEffective(leaseId),
                    properties.sharedEirpLimitMw());
            if (!report.compatible()) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.CONFLICT.value(), "LEASE_CONFLICT", toJson(report), null, now);
                return TxOutcome.failure(HttpStatus.CONFLICT.value(), "LEASE_CONFLICT", report);
            }
            int newVersion = header.currentVersion() + 1;
            repository.insertVersion(leaseId, newVersion, spec, request.requestId(), now);
            repository.updateCurrentVersion(leaseId, newVersion, now);
            repository.insertEvent(leaseId, newVersion, request.requestId(), EventType.MODIFIED, now);
            idempotencyRecorder.record(request.requestId(), fingerprint,
                    HttpStatus.OK.value(), null, null, leaseId, now);
            LeaseResponse response = new LeaseResponse(leaseId, newVersion, LeaseStatus.ACTIVE,
                    spec.startTime(), spec.endTime(), spec.freqLowMhz(), spec.freqHighMhz(),
                    spec.mode(), spec.eirpMw(), spec.cells(), null, false);
            return TxOutcome.success(response);
        });
        return settle(outcome);
    }

    /**
     * 取消租约（终态）。重复同参取消是无副作用成功且不新增事件；
     * 对已取消租约再次取消（无论幂等键新旧）同样幂等成功。
     */
    public LeaseResponse cancel(long leaseId, LeaseCancelRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new InvalidLeaseRequestException("requestId must be non-blank");
        }
        if (request.expectedVersion() == null) {
            throw new InvalidLeaseRequestException("expectedVersion is required for cancel");
        }
        String fingerprint = fingerprint("CANCEL", leaseId, request.expectedVersion(), null);
        long now = clock.nowMillis();
        TxOutcome outcome = txTemplate.execute(status -> {
            repository.lockCoordination();
            LeaseRepository.IdempotencyRow existing = repository.findIdempotency(request.requestId());
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new IdempotencyConflictException(request.requestId());
                }
                return TxOutcome.replay(existing);
            }
            LeaseHeader header = repository.findHeader(leaseId);
            if (header == null) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.NOT_FOUND.value(), "LEASE_NOT_FOUND", null, null, now);
                return TxOutcome.failure(HttpStatus.NOT_FOUND.value(), "LEASE_NOT_FOUND", null);
            }
            if (header.status() == LeaseStatus.CANCELLED) {
                // 终态幂等：重复取消不新增事件，直接返回当前状态。
                LeaseResponse response = responseFor(header);
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.OK.value(), null, null, leaseId, now);
                return TxOutcome.success(response);
            }
            if (header.currentVersion() != request.expectedVersion()) {
                idempotencyRecorder.record(request.requestId(), fingerprint,
                        HttpStatus.CONFLICT.value(), "VERSION_CONFLICT", null, null, now);
                return TxOutcome.failure(HttpStatus.CONFLICT.value(), "VERSION_CONFLICT", null);
            }
            repository.markCancelled(leaseId, now, now);
            repository.insertEvent(leaseId, header.currentVersion(), request.requestId(),
                    EventType.CANCELLED, now);
            idempotencyRecorder.record(request.requestId(), fingerprint,
                    HttpStatus.OK.value(), null, null, leaseId, now);
            return TxOutcome.success(responseFor(repository.findHeader(leaseId)));
        });
        return settle(outcome);
    }

    /** 读取租约当前状态与版本。 */
    public LeaseResponse getLease(long leaseId) {
        LeaseHeader header = repository.findHeader(leaseId);
        if (header == null) {
            throw new LeaseNotFoundException(leaseId);
        }
        return responseFor(header);
    }

    /** 指定时刻（epoch 毫秒 UTC，null 表示当前时刻）的活动租约快照。 */
    public List<LeaseResponse> activeSnapshot(Long at) {
        long atMillis = at != null ? at : clock.nowMillis();
        List<LeaseResponse> result = new ArrayList<>();
        for (LeaseRepository.CurrentLeaseRow row : repository.findAllCurrent()) {
            LeaseHeader header = row.header();
            LeaseSpec spec = row.spec();
            long effectiveEnd = header.cancelTime() != null
                    ? Math.min(spec.endTime(), header.cancelTime()) : spec.endTime();
            if (Intervals.contains(spec.startTime(), effectiveEnd, atMillis)) {
                result.add(new LeaseResponse(header.leaseId(), header.currentVersion(),
                        header.status(), spec.startTime(), spec.endTime(),
                        spec.freqLowMhz(), spec.freqHighMhz(), spec.mode(), spec.eirpMw(),
                        spec.cells(), header.cancelTime(), false));
            }
        }
        return result;
    }

    /** 对候选租约规格做冲突解释，不写入任何数据。 */
    public ConflictResponse explainCandidate(LeaseUpsertRequest request) {
        LeaseSpec spec = toSpec(request);
        ConflictReport report = ConflictAnalyzer.analyze(spec, null, 0,
                loadEffective(null), properties.sharedEirpLimitMw());
        return new ConflictResponse(null, report);
    }

    /** 对已存在租约的当前版本重新做冲突解释（不写入）。 */
    public ConflictResponse explainLease(long leaseId) {
        LeaseHeader header = repository.findHeader(leaseId);
        if (header == null) {
            throw new LeaseNotFoundException(leaseId);
        }
        LeaseRepository.LeaseVersionRecord record =
                repository.findVersionRecord(leaseId, header.currentVersion());
        ConflictReport report = ConflictAnalyzer.analyze(record.spec(), leaseId,
                header.currentVersion(), loadEffective(leaseId), properties.sharedEirpLimitMw());
        return new ConflictResponse(leaseId, report);
    }

    /** 区域 × 频段占用查询（可选时间窗），返回与查询窗口相遇的活动租约版本。 */
    public List<OccupancyItem> occupancy(List<String> cells, Double freqLowMhz, Double freqHighMhz,
                                         Long startTime, Long endTime) {
        if (freqLowMhz != null && freqHighMhz != null && !(freqLowMhz < freqHighMhz)) {
            throw new InvalidLeaseRequestException("freqLow must be less than freqHigh");
        }
        if (startTime != null && endTime != null && startTime >= endTime) {
            throw new InvalidLeaseRequestException("start must be less than end");
        }
        List<OccupancyItem> result = new ArrayList<>();
        for (LeaseRepository.CurrentLeaseRow row : repository.findAllCurrent()) {
            LeaseHeader header = row.header();
            LeaseSpec spec = row.spec();
            long effectiveEnd = header.cancelTime() != null
                    ? Math.min(spec.endTime(), header.cancelTime()) : spec.endTime();
            if (cells != null && !cells.isEmpty()
                    && spec.cells().stream().noneMatch(cells::contains)) {
                continue;
            }
            if (freqLowMhz != null && freqHighMhz != null
                    && !Intervals.overlaps(spec.freqLowMhz(), spec.freqHighMhz(),
                    freqLowMhz, freqHighMhz)) {
                continue;
            }
            if (startTime != null && endTime != null
                    && !Intervals.overlaps(spec.startTime(), effectiveEnd, startTime, endTime)) {
                continue;
            }
            result.add(new OccupancyItem(header.leaseId(), header.currentVersion(), spec.mode(),
                    spec.eirpMw(), spec.startTime(), spec.endTime(),
                    spec.freqLowMhz(), spec.freqHighMhz()));
        }
        return result;
    }

    /**
     * 事务结束后统一处理结果：回放首次结果、返回成功或抛出失败异常。
     * 幂等记录已在协调事务内随业务结果一并提交。
     */
    private LeaseResponse settle(TxOutcome outcome) {
        if (outcome.replay() != null) {
            return replay(outcome.replay());
        }
        if (outcome.success() != null) {
            return outcome.success();
        }
        throw toException(outcome);
    }

    /** 回放首次请求结果。 */
    private LeaseResponse replay(LeaseRepository.IdempotencyRow row) {
        if (row.errorCode() != null) {
            ConflictReport report = null;
            if (row.errorBody() != null) {
                report = fromJson(row.errorBody());
            }
            throw toException(new TxOutcome(null, null, row.httpStatus(),
                    row.errorCode(), report));
        }
        if (row.resultLeaseId() == null) {
            throw new IllegalStateException("idempotency record without result");
        }
        LeaseHeader header = repository.findHeader(row.resultLeaseId());
        if (header == null) {
            throw new IllegalStateException("lease disappeared for idempotent replay");
        }
        Integer version = repository.findEventVersionByRequest(row.requestId());
        LeaseResponse response = responseFor(header, version);
        return new LeaseResponse(response.leaseId(), response.version(), response.status(),
                response.startTime(), response.endTime(), response.freqLowMhz(),
                response.freqHighMhz(), response.mode(), response.eirpMw(), response.cells(),
                response.cancelTime(), true);
    }

    private static LeaseServiceException toException(TxOutcome outcome) {
        return switch (outcome.errorCode()) {
            case "LEASE_CONFLICT" -> new LeaseConflictException(outcome.report());
            case "LEASE_NOT_FOUND" -> new LeaseServiceException("LEASE_NOT_FOUND",
                    HttpStatus.NOT_FOUND, "lease not found");
            case "LEASE_TERMINAL" -> new LeaseServiceException("LEASE_TERMINAL",
                    HttpStatus.CONFLICT, "lease is cancelled and cannot be modified");
            case "VERSION_CONFLICT" -> new LeaseServiceException("VERSION_CONFLICT",
                    HttpStatus.CONFLICT, "expectedVersion does not match current version");
            default -> new LeaseServiceException(outcome.errorCode(),
                    HttpStatus.CONFLICT, "request rejected: " + outcome.errorCode());
        };
    }

    /** 校验并转换请求为租约规格。 */
    private LeaseSpec toSpec(LeaseUpsertRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new InvalidLeaseRequestException("requestId must be non-blank");
        }
        if (request.startTime() == null || request.endTime() == null
                || request.startTime() >= request.endTime()) {
            throw new InvalidLeaseRequestException("startTime must be less than endTime");
        }
        if (request.freqLowMhz() == null || request.freqHighMhz() == null
                || !(request.freqLowMhz() < request.freqHighMhz())) {
            throw new InvalidLeaseRequestException("freqLowMhz must be less than freqHighMhz");
        }
        if (request.mode() == null) {
            throw new InvalidLeaseRequestException("mode is required");
        }
        if (request.eirpMw() == null || !(request.eirpMw() > 0)) {
            throw new InvalidLeaseRequestException("eirpMw must be positive");
        }
        if (request.cells() == null || request.cells().isEmpty()) {
            throw new InvalidLeaseRequestException("cells must be non-empty");
        }
        SortedSet<String> cells = new TreeSet<>();
        for (String cell : request.cells()) {
            if (cell == null || cell.isBlank()) {
                throw new InvalidLeaseRequestException("cell must be non-blank");
            }
            cells.add(cell.trim());
        }
        return new LeaseSpec(request.startTime(), request.endTime(),
                request.freqLowMhz(), request.freqHighMhz(), request.mode(),
                request.eirpMw(), cells);
    }

    /**
     * 请求指纹：覆盖操作类型、租约、版本、区域、时间、频段、模式与功率。
     */
    private static String fingerprint(String op, Long leaseId, int expectedVersion, LeaseSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("op=").append(op);
        sb.append(";lease=").append(leaseId == null ? "-" : leaseId);
        sb.append(";ver=").append(expectedVersion);
        if (spec != null) {
            sb.append(";cells=").append(String.join(",", spec.cells()));
            sb.append(";start=").append(spec.startTime());
            sb.append(";end=").append(spec.endTime());
            sb.append(";low=").append(spec.freqLowMhz());
            sb.append(";high=").append(spec.freqHighMhz());
            sb.append(";mode=").append(spec.mode());
            sb.append(";eirp=").append(spec.eirpMw());
        }
        return Fingerprints.sha256Hex(sb.toString());
    }

    /**
     * 加载协调判定所需的生效租约集合：取每个租约当前版本，
     * 取消租约的时间区间按取消时刻裁剪（取消只改变未来有效性），
     * 修改时排除被修改租约自身。
     */
    private List<ConflictAnalyzer.EffectiveLease> loadEffective(Long excludeLeaseId) {
        List<ConflictAnalyzer.EffectiveLease> result = new ArrayList<>();
        for (LeaseRepository.CurrentLeaseRow row : repository.findAllCurrent()) {
            LeaseHeader header = row.header();
            if (excludeLeaseId != null && header.leaseId() == excludeLeaseId) {
                continue;
            }
            LeaseSpec spec = row.spec();
            long effectiveEnd = header.cancelTime() != null
                    ? Math.min(spec.endTime(), header.cancelTime()) : spec.endTime();
            if (spec.startTime() >= effectiveEnd) {
                continue;
            }
            result.add(ConflictAnalyzer.EffectiveLease.of(header.leaseId(),
                    header.currentVersion(), spec, spec.startTime(), effectiveEnd, spec.cells()));
        }
        return result;
    }

    /** 由租约头构造当前版本响应。 */
    private LeaseResponse responseFor(LeaseHeader header) {
        return responseFor(header, header.currentVersion());
    }

    /** 由租约头与指定版本构造响应（幂等回放首次结果时使用历史版本）。 */
    private LeaseResponse responseFor(LeaseHeader header, Integer version) {
        int v = version != null ? version : header.currentVersion();
        LeaseRepository.LeaseVersionRecord record =
                repository.findVersionRecord(header.leaseId(), v);
        if (record == null) {
            record = repository.findVersionRecord(header.leaseId(), header.currentVersion());
        }
        LeaseSpec spec = record.spec();
        return new LeaseResponse(header.leaseId(), v, header.status(),
                spec.startTime(), spec.endTime(), spec.freqLowMhz(), spec.freqHighMhz(),
                spec.mode(), spec.eirpMw(), record.cells(), header.cancelTime(), false);
    }

    private String toJson(ConflictReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize conflict report", e);
        }
    }

    private ConflictReport fromJson(String body) {
        try {
            return objectMapper.readValue(body, ConflictReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict report", e);
        }
    }

    /**
     * 协调事务的结果：成功响应、失败（状态码 + 错误码 + 可选冲突报告）或幂等回放。
     */
    private record TxOutcome(LeaseResponse success,
                             LeaseRepository.IdempotencyRow replay,
                             int httpStatus,
                             String errorCode,
                             ConflictReport report) {

        static TxOutcome success(LeaseResponse response) {
            return new TxOutcome(response, null, 0, null, null);
        }

        static TxOutcome replay(LeaseRepository.IdempotencyRow row) {
            return new TxOutcome(null, row, 0, null, null);
        }

        static TxOutcome failure(int httpStatus, String errorCode, ConflictReport report) {
            return new TxOutcome(null, null, httpStatus, errorCode, report);
        }
    }
}
