package com.example.starter.schedule;

import com.example.starter.common.ApiException;
import com.example.starter.common.GlobalExceptionHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 编排核心服务：申请受理（幂等、冲突消解、优先级抢占）、撤销、按日发布与版本查询。
 * 冲突判定基于半开区间 [start, end)：端点相接不算冲突，内部重叠即冲突。
 */
@Service
public class ScheduleService {

    /**
     * 已存储的响应：用于幂等重放与统一返回。
     *
     * @param httpStatus HTTP 状态码
     * @param body       响应体 JSON
     */
    public record StoredResponse(int httpStatus, String body) {
    }

    private final ScheduleRepository repository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public ScheduleService(ScheduleRepository repository,
                           PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 受理编排申请。以 requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不改变编排表。
     */
    public StoredResponse submit(ScheduleDtos.SubmitRequest request) {
        validate(request);
        ScheduleRepository.WindowRow window = repository.findWindow(request.windowCode())
                .orElseThrow(() -> ApiException.notFound("WINDOW_NOT_FOUND", "过境窗口不存在: " + request.windowCode()));
        if (!window.satelliteCode().equals(request.satelliteCode())
                || !window.stationCode().equals(request.stationCode())) {
            throw ApiException.badRequest("WINDOW_MISMATCH", "窗口与卫星/地面站不匹配");
        }
        String fingerprint = fingerprint(request);
        Optional<ScheduleRepository.RequestRow> existing = repository.findRequest(request.requestId());
        if (existing.isPresent()) {
            ScheduleRepository.RequestRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 携带不同参数，拒绝且编排表不变");
            }
            return new StoredResponse(row.httpStatus(), row.responseBody());
        }
        try {
            return txTemplate.execute(status -> doSubmit(request, window, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束兜底：同 requestId 他事务已提交则重放其结果；否则同站同窗仅一个成功
            Optional<ScheduleRepository.RequestRow> won = repository.findRequest(request.requestId());
            if (won.isPresent() && won.get().fingerprint().equals(fingerprint)) {
                return new StoredResponse(won.get().httpStatus(), won.get().responseBody());
            }
            throw ApiException.conflict("CONCURRENT_CONFLICT",
                    "并发申请冲突：同一地面站同一窗口仅允许一个成功");
        }
    }

    private StoredResponse doSubmit(ScheduleDtos.SubmitRequest request,
                                    ScheduleRepository.WindowRow window, String fingerprint) {
        boolean frozen = repository.existsPublication(window.day());
        List<ScheduleRepository.EntryRow> conflicts = repository.findConflictingEntries(
                window.stationId(), window.startUtc(), window.endUtc());
        List<ScheduleDtos.PreemptedInfo> preempted = new ArrayList<>();
        if (!conflicts.isEmpty()) {
            boolean canPreempt = !frozen
                    && conflicts.stream().allMatch(c -> c.priority() < request.priority());
            if (!canPreempt) {
                String code = frozen ? "DAY_FROZEN" : "WINDOW_CONFLICT";
                String message = frozen
                        ? "该日编排已发布冻结，抢占与冲突申请一律拒绝"
                        : "窗口冲突：同优先级先到者保留，或对方优先级更高";
                List<ScheduleDtos.ConflictInfo> details = conflicts.stream().map(this::toConflictInfo).toList();
                return store(request, window, fingerprint, 409,
                        toJson(new GlobalExceptionHandler.ErrorBody(code, message, details)));
            }
            for (ScheduleRepository.EntryRow c : conflicts) {
                repository.markPreempted(c.id(), request.requestId());
                List<String> candidates = repository.findReplanCandidates(
                        c.satelliteId(), c.stationId(), c.endUtc(), c.windowId());
                repository.insertSuggestion(c.id(), c.satelliteId(), c.stationId(),
                        String.join(",", candidates));
                preempted.add(new ScheduleDtos.PreemptedInfo(c.id(), c.requestId(), c.windowCode(), candidates));
            }
        }
        long entryId = repository.insertEntry(request.requestId(), window.satelliteId(),
                window.stationId(), window.id(), request.priority(), window.day());
        String outcome = preempted.isEmpty() ? "SCHEDULED" : "PREEMPTED_OTHERS";
        return store(request, window, fingerprint, 200, toJson(
                new ScheduleDtos.SubmitAccepted(request.requestId(), outcome, entryId, preempted)));
    }

    private StoredResponse store(ScheduleDtos.SubmitRequest request, ScheduleRepository.WindowRow window,
                                 String fingerprint, int httpStatus, String body) {
        repository.insertRequest(request.requestId(), fingerprint, window.satelliteId(),
                window.stationId(), window.id(), request.priority(), httpStatus, body);
        return new StoredResponse(httpStatus, body);
    }

    /**
     * 按日发布：首次发布冻结该日；无新增草稿时重复发布返回幂等成功；有紧急插入草稿时发布新版本。
     */
    public ScheduleDtos.PublishResult publish(LocalDate day) {
        return txTemplate.execute(status -> {
            Optional<Integer> max = repository.maxVersion(day);
            List<ScheduleRepository.EntryRow> unpublished = repository.findUnpublishedEntries(day);
            if (max.isPresent() && unpublished.isEmpty()) {
                return new ScheduleDtos.PublishResult(day.toString(), max.get(), 0, true);
            }
            int version = max.orElse(0) + 1;
            repository.assignVersion(day, version);
            repository.insertPublication(day, version, unpublished.size());
            return new ScheduleDtos.PublishResult(day.toString(), version, unpublished.size(), false);
        });
    }

    /**
     * 撤销编排条目。已发布冻结日拒绝撤销；重复撤销幂等返回。
     */
    public ScheduleDtos.EntryView cancel(long entryId) {
        return txTemplate.execute(status -> {
            ScheduleRepository.EntryRow entry = repository.findEntry(entryId)
                    .orElseThrow(() -> ApiException.notFound("ENTRY_NOT_FOUND", "编排条目不存在: " + entryId));
            if ("CANCELLED".equals(entry.status())) {
                return toView(entry);
            }
            if (repository.existsPublication(entry.day())) {
                throw ApiException.conflict("DAY_FROZEN", "该日编排已发布冻结，撤销申请被拒绝");
            }
            if (!"SCHEDULED".equals(entry.status())) {
                throw ApiException.conflict("INVALID_STATE", "仅已编排条目可撤销，当前状态: " + entry.status());
            }
            repository.markCancelled(entryId);
            return toView(repository.findEntry(entryId).orElseThrow());
        });
    }

    public List<ScheduleDtos.EntryView> listEntries(LocalDate day) {
        return repository.findEntriesByDay(day).stream().map(this::toView).toList();
    }

    public List<ScheduleDtos.PublicationView> listPublications(LocalDate day) {
        return repository.listPublicationRows(day).stream()
                .map(p -> new ScheduleDtos.PublicationView(p.version(), p.entryCount(),
                        p.publishedAt().atOffset(ZoneOffset.UTC).toString()))
                .toList();
    }

    /**
     * 查询某日指定版本快照（含历史版本）：纳入版本号不超过 version 的全部已发布条目。
     */
    public List<ScheduleDtos.EntryView> snapshot(LocalDate day, int version) {
        int max = repository.maxVersion(day)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "该日尚无发布版本: " + day));
        if (version < 1 || version > max) {
            throw ApiException.notFound("VERSION_NOT_FOUND", "版本不存在: " + day + " v" + version);
        }
        return repository.findSnapshot(day, version).stream().map(this::toView).toList();
    }

    private void validate(ScheduleDtos.SubmitRequest request) {
        if (request == null || isBlank(request.requestId()) || isBlank(request.satelliteCode())
                || isBlank(request.stationCode()) || isBlank(request.windowCode())) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "requestId/卫星/地面站/窗口均为必填");
        }
        if (request.priority() == null || request.priority() < 1 || request.priority() > 5) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "优先级必须为 1-5，5 最高");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String fingerprint(ScheduleDtos.SubmitRequest request) {
        String material = String.join("|", request.requestId(), request.satelliteCode(),
                request.stationCode(), request.windowCode(), String.valueOf(request.priority()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ScheduleDtos.ConflictInfo toConflictInfo(ScheduleRepository.EntryRow row) {
        return new ScheduleDtos.ConflictInfo(row.id(), row.requestId(), row.windowCode(),
                row.startUtc().atOffset(ZoneOffset.UTC).toString(),
                row.endUtc().atOffset(ZoneOffset.UTC).toString(), row.priority());
    }

    private ScheduleDtos.EntryView toView(ScheduleRepository.EntryRow row) {
        return new ScheduleDtos.EntryView(row.id(), row.requestId(), row.windowCode(),
                row.satelliteCode(), row.stationCode(),
                row.startUtc().atOffset(ZoneOffset.UTC).toString(),
                row.endUtc().atOffset(ZoneOffset.UTC).toString(),
                row.priority(), row.status(), row.publishedVersion(), row.preemptedByRequestId());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
