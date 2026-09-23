package com.example.starter.spectrum.service;

import com.example.starter.spectrum.ConflictDetail;
import com.example.starter.spectrum.api.CancelLeaseRequest;
import com.example.starter.spectrum.api.CreateLeaseRequest;
import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.ModifyLeaseRequest;
import com.example.starter.spectrum.config.SpectrumProperties;
import com.example.starter.spectrum.db.LeaseRepository;
import com.example.starter.spectrum.domain.ActiveLease;
import com.example.starter.spectrum.domain.LeaseAggregate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 频谱租约协调服务实现。
 *
 * <p>所有创建/修改/取消均在单事务内完成，并首先持有单行协调锁
 * （SELECT ... FOR UPDATE）：并发写事务串行执行，冲突检测与共享功率汇总
 * 始终基于最新已提交的活动租约集合，得到可串行化结果。</p>
 */
@Service
public class LeaseServiceImpl implements LeaseService {

    private final LeaseRepository repository;
    private final SpectrumProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public LeaseServiceImpl(LeaseRepository repository, SpectrumProperties properties,
                            Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ServiceResult create(CreateLeaseRequest request) {
        Instant now = Instant.now(clock);
        repository.acquireCoordinationLock();

        String fingerprint = FingerprintUtil.sha256(
                "CREATE",
                String.join(",", new TreeSet<>(request.regions())),
                request.start().toString(), request.end().toString(),
                request.freqLowMHz().stripTrailingZeros().toPlainString(),
                request.freqHighMHz().stripTrailingZeros().toPlainString(),
                request.mode().name(),
                request.eirpMw().stripTrailingZeros().toPlainString());

        Optional<ServiceResult> replay = lookupIdempotent(request.requestId(), "CREATE", fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        Optional<ServiceResult> invalid = validateWindow(request.regions(), request.start(),
                request.end(), request.freqLowMHz(), request.freqHighMHz());
        if (invalid.isPresent()) {
            return settleIdempotent(request.requestId(), "CREATE", fingerprint,
                    invalid.get(), null, now);
        }

        String leaseId = "L-" + UUID.randomUUID();
        ActiveLease candidate = new ActiveLease(leaseId, 1, request.start(), request.end(),
                request.freqLowMHz(), request.freqHighMHz(), request.mode(), request.eirpMw(),
                Set.copyOf(request.regions()));

        List<ActiveLease> existing = repository.loadActiveLeases(
                request.start(), request.end(), request.regions());
        List<ConflictDetail> conflicts = ConflictDetector.detect(
                candidate, existing, properties.sharedEirpLimitMw());
        if (!conflicts.isEmpty()) {
            ServiceResult rejected = conflictResult(conflicts);
            return settleIdempotent(request.requestId(), "CREATE", fingerprint,
                    rejected, null, now);
        }

        repository.insertLease(leaseId, 1, request.start(), request.end(),
                request.freqLowMHz(), request.freqHighMHz(), request.mode().name(),
                request.eirpMw(), Set.copyOf(request.regions()), now);
        LeaseAggregate saved = repository.loadLease(leaseId).orElseThrow();
        ServiceResult ok = new ServiceResult(201, toResponse(saved), leaseId);
        return settleIdempotent(request.requestId(), "CREATE", fingerprint, ok, leaseId, now);
    }

    @Override
    @Transactional
    public ServiceResult modify(ModifyLeaseRequest request) {
        Instant now = Instant.now(clock);
        repository.acquireCoordinationLock();

        String fingerprint = FingerprintUtil.sha256(
                "MODIFY", request.leaseId(), String.valueOf(request.expectedVersion()),
                String.join(",", new TreeSet<>(request.regions())),
                request.start().toString(), request.end().toString(),
                request.freqLowMHz().stripTrailingZeros().toPlainString(),
                request.freqHighMHz().stripTrailingZeros().toPlainString(),
                request.mode().name(),
                request.eirpMw().stripTrailingZeros().toPlainString());

        Optional<ServiceResult> replay = lookupIdempotent(request.requestId(), "MODIFY", fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        Optional<LeaseAggregate> loaded = repository.loadLease(request.leaseId());
        if (loaded.isEmpty()) {
            ServiceResult notFound = errorResult(404, "LEASE_NOT_FOUND",
                    "租约不存在: " + request.leaseId());
            return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                    notFound, request.leaseId(), now);
        }
        LeaseAggregate lease = loaded.get();
        if (lease.currentVersion() != request.expectedVersion()) {
            ServiceResult mismatch = errorResult(409, "VERSION_MISMATCH",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 "
                            + lease.currentVersion() + " 不一致");
            return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                    mismatch, request.leaseId(), now);
        }
        if ("CANCELLED".equals(lease.status())) {
            ServiceResult terminated = errorResult(409, "LEASE_TERMINATED",
                    "租约已取消，不能修改");
            return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                    terminated, request.leaseId(), now);
        }

        Optional<ServiceResult> invalid = validateWindow(request.regions(), request.start(),
                request.end(), request.freqLowMHz(), request.freqHighMHz());
        if (invalid.isPresent()) {
            return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                    invalid.get(), request.leaseId(), now);
        }

        int newVersion = lease.currentVersion() + 1;
        ActiveLease candidate = new ActiveLease(request.leaseId(), newVersion,
                request.start(), request.end(), request.freqLowMHz(), request.freqHighMHz(),
                request.mode(), request.eirpMw(), Set.copyOf(request.regions()));

        List<ActiveLease> existing = repository.loadActiveLeases(
                request.start(), request.end(), request.regions())
                .stream().filter(a -> !a.leaseId().equals(request.leaseId())).toList();
        List<ConflictDetail> conflicts = ConflictDetector.detect(
                candidate, existing, properties.sharedEirpLimitMw());
        if (!conflicts.isEmpty()) {
            ServiceResult rejected = conflictResult(conflicts);
            return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                    rejected, request.leaseId(), now);
        }

        repository.appendVersion(request.leaseId(), newVersion, request.start(), request.end(),
                request.freqLowMHz(), request.freqHighMHz(), request.mode().name(),
                request.eirpMw(), Set.copyOf(request.regions()), now);
        LeaseAggregate saved = repository.loadLease(request.leaseId()).orElseThrow();
        ServiceResult ok = new ServiceResult(200, toResponse(saved), request.leaseId());
        return settleIdempotent(request.requestId(), "MODIFY", fingerprint,
                ok, request.leaseId(), now);
    }

    @Override
    @Transactional
    public ServiceResult cancel(CancelLeaseRequest request) {
        Instant now = Instant.now(clock);
        repository.acquireCoordinationLock();

        String fingerprint = FingerprintUtil.sha256(
                "CANCEL", request.leaseId(), String.valueOf(request.expectedVersion()));

        Optional<ServiceResult> replay = lookupIdempotent(request.requestId(), "CANCEL", fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        Optional<LeaseAggregate> loaded = repository.loadLease(request.leaseId());
        if (loaded.isEmpty()) {
            ServiceResult notFound = errorResult(404, "LEASE_NOT_FOUND",
                    "租约不存在: " + request.leaseId());
            return settleIdempotent(request.requestId(), "CANCEL", fingerprint,
                    notFound, request.leaseId(), now);
        }
        LeaseAggregate lease = loaded.get();
        if (lease.currentVersion() != request.expectedVersion()) {
            ServiceResult mismatch = errorResult(409, "VERSION_MISMATCH",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 "
                            + lease.currentVersion() + " 不一致");
            return settleIdempotent(request.requestId(), "CANCEL", fingerprint,
                    mismatch, request.leaseId(), now);
        }

        // 终态幂等：已取消租约同参再次取消，无副作用成功，不新增租约事件
        ServiceResult result;
        if ("CANCELLED".equals(lease.status())) {
            result = new ServiceResult(200, toResponse(lease), request.leaseId());
        } else {
            repository.markCancelled(request.leaseId(), lease.currentVersion(), now);
            LeaseAggregate saved = repository.loadLease(request.leaseId()).orElseThrow();
            result = new ServiceResult(200, toResponse(saved), request.leaseId());
        }
        return settleIdempotent(request.requestId(), "CANCEL", fingerprint,
                result, request.leaseId(), now);
    }

    @Override
    public List<LeaseResponse> activeSnapshot(Instant at) {
        return repository.loadActiveAt(at).stream().map(LeaseServiceImpl::toResponse).toList();
    }

    @Override
    public List<LeaseResponse> occupancy(Instant at, List<String> regions,
                                         BigDecimal freqLowMHz, BigDecimal freqHighMHz) {
        Set<String> wanted = new TreeSet<>(regions);
        return repository.loadActiveAt(at).stream()
                .filter(lease -> lease.regions().stream().anyMatch(wanted::contains))
                .filter(lease -> lease.freqHighMHz().compareTo(freqLowMHz) > 0
                        && lease.freqLowMHz().compareTo(freqHighMHz) < 0)
                .map(LeaseServiceImpl::toResponse)
                .toList();
    }

    @Override
    public LeaseAggregate requireLease(String leaseId) {
        return repository.loadLease(leaseId)
                .orElseThrow(() -> new com.example.starter.spectrum.LeaseConflictException(
                        "LEASE_NOT_FOUND", "租约不存在: " + leaseId, List.of()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConflictDetail> explain(Set<String> regions, Instant start, Instant end,
                                        BigDecimal freqLowMHz, BigDecimal freqHighMHz,
                                        com.example.starter.spectrum.LeaseMode mode,
                                        BigDecimal eirpMw) {
        validateWindow(regions, start, end, freqLowMHz, freqHighMHz).ifPresent(sr -> {
            throw new com.example.starter.spectrum.LeaseConflictException(
                    "INVALID_PROPOSAL",
                    ((Map<?, ?>) sr.body()).get("message").toString(), List.of());
        });
        ActiveLease candidate = new ActiveLease("proposed", 0, start, end,
                freqLowMHz, freqHighMHz, mode, eirpMw, Set.copyOf(regions));
        List<ActiveLease> existing = repository.loadActiveLeases(start, end, regions);
        return ConflictDetector.detect(candidate, existing, properties.sharedEirpLimitMw());
    }

    /**
     * 在协调锁保护下查询幂等记录：同键同参回放首次结果；同键异参返回 409。
     */
    private Optional<ServiceResult> lookupIdempotent(String requestId, String kind,
                                                     String fingerprint) {
        Optional<LeaseRepository.IdempotentRecord> record =
                repository.findIdempotentRecord(requestId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        LeaseRepository.IdempotentRecord first = record.get();
        if (!first.fingerprint().equals(fingerprint) || !first.requestKind().equals(kind)) {
            return Optional.of(errorResult(409, "IDEMPOTENCY_MISMATCH",
                    "requestId 已用于参数不同的请求"));
        }
        try {
            // 成功落定的响应体是租约视图；错误响应保持通用 Map 结构
            Class<?> bodyType = first.httpStatus() == 200 || first.httpStatus() == 201
                    ? LeaseResponse.class : Object.class;
            Object body = objectMapper.readValue(first.responseBody(), bodyType);
            return Optional.of(new ServiceResult(first.httpStatus(), body, first.leaseId()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 落定一次请求：持久化幂等记录并返回其结果。
     * 若同一 requestId 的并发事务抢先落定，则回放先落定的结果。
     */
    private ServiceResult settleIdempotent(String requestId, String kind, String fingerprint,
                                           ServiceResult result, String leaseId, Instant now) {
        String body;
        try {
            body = objectMapper.writeValueAsString(result.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        try {
            repository.insertIdempotentRecord(new LeaseRepository.IdempotentRecord(
                    requestId, kind, leaseId, fingerprint, result.httpStatus(), body), now);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return lookupIdempotent(requestId, kind, fingerprint)
                    .orElseThrow(() -> duplicate);
        }
        return result;
    }

    private Optional<ServiceResult> validateWindow(Set<String> regions, Instant start, Instant end,
                                                   BigDecimal freqLowMHz, BigDecimal freqHighMHz) {
        if (!end.isAfter(start)) {
            return Optional.of(errorResult(400, "INVALID_TIME_WINDOW",
                    "结束时刻必须晚于开始时刻（半开区间 [start,end)）"));
        }
        if (freqHighMHz.compareTo(freqLowMHz) <= 0) {
            return Optional.of(errorResult(400, "INVALID_FREQ_WINDOW",
                    "频率上限必须大于下限（半开区间 [low,high)）"));
        }
        if (regions.stream().map(String::trim).anyMatch(String::isEmpty)) {
            return Optional.of(errorResult(400, "INVALID_REGION", "区域单元标识不能为空"));
        }
        return Optional.empty();
    }

    private ServiceResult conflictResult(List<ConflictDetail> conflicts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "LEASE_CONFLICT");
        body.put("message", "租约协调未通过：存在独占冲突或共享片段 EIRP 超限");
        body.put("details", conflicts);
        return new ServiceResult(409, body, null);
    }

    private static ServiceResult errorResult(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return new ServiceResult(status, body, null);
    }

    private static LeaseResponse toResponse(LeaseAggregate aggregate) {
        List<LeaseResponse.EventView> events = aggregate.events().stream()
                .map(e -> new LeaseResponse.EventView(
                        e.version(), e.eventType(), e.detail(), e.createdAtUtc()))
                .toList();
        return new LeaseResponse(
                aggregate.leaseId(), aggregate.currentVersion(), aggregate.status(),
                aggregate.regions(), aggregate.start(), aggregate.end(),
                aggregate.freqLowMHz(), aggregate.freqHighMHz(), aggregate.mode(),
                aggregate.eirpMw(), aggregate.cancelledAtUtc(), events);
    }
}
