package com.example.starter.rail.service;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.RequestResponse;
import com.example.starter.rail.dto.SnapshotResponse;
import com.example.starter.rail.dto.TransitionRequest;
import com.example.starter.rail.model.ConflictDetail;
import com.example.starter.rail.model.IdempotencyRecord;
import com.example.starter.rail.model.PlanSnapshot;
import com.example.starter.rail.model.PossessionRequest;
import com.example.starter.rail.model.RequestEvent;
import com.example.starter.rail.model.RequestStatus;
import com.example.starter.rail.repo.PossessionRepository;
import com.example.starter.rail.web.ApprovalConflictException;
import com.example.starter.rail.web.ConflictException;
import com.example.starter.rail.web.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 铁路检修封锁窗协调服务。
 *
 * <p>审批在单事务内对全部区段加行锁并原子完成；无共同区段的申请互不阻塞，
 * 共用区段的审批串行化，正长度重叠（半开区间，端点相接不冲突）即整笔拒绝。
 * 紧急抢占（PREEMPT 模式）在同一事务内抢占全部与之冲突、尚未激活且严格低优先级
 * 的窗口；只要存在已激活、同级/更高优先级窗口或仅能部分抢占，整笔拒绝回滚。
 */
@Service
public class PossessionService {

    private final PossessionRepository repo;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;

    public PossessionService(PossessionRepository repo, TimeSource timeSource,
                             ObjectMapper objectMapper) {
        this.repo = repo;
        this.timeSource = timeSource;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequestResponse create(CreateRequestRequest request) {
        if (!request.end().isAfter(request.start())) {
            throw new ConflictException("end must be after start; window is half-open [start,end)");
        }
        List<String> sections = request.sectionIds().stream().distinct().toList();
        if (sections.size() != request.sectionIds().size()) {
            throw new ConflictException("sectionIds must be unique within one request");
        }
        long now = timeSource.nowMillis();
        PossessionRequest entity = new PossessionRequest();
        entity.setRequestId(request.requestId());
        entity.setStartTime(request.start().toEpochMilli());
        entity.setEndTime(request.end().toEpochMilli());
        entity.setBufferBeforeMs(request.bufferBeforeMs());
        entity.setBufferAfterMs(request.bufferAfterMs());
        entity.setPriority(request.priority());
        entity.setVersion(0);
        entity.setStatus(RequestStatus.PENDING);
        entity.setSectionIds(sections);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            repo.insertRequest(entity);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("requestId already exists: " + request.requestId());
        }
        repo.insertSections(request.requestId(), sections, now);

        RequestEvent submitted = new RequestEvent();
        submitted.setRequestId(request.requestId());
        submitted.setEventType("SUBMITTED");
        submitted.setFromStatus(null);
        submitted.setToStatus(RequestStatus.PENDING);
        submitted.setBusinessTime(null);
        submitted.setOccurredAt(now);
        submitted.setFingerprint(null);
        repo.appendEvent(submitted);
        return RequestResponse.from(entity);
    }

    public RequestResponse get(String requestId) {
        PossessionRequest request = requireRequest(requestId);
        request.setSectionIds(repo.findSections(requestId));
        return RequestResponse.from(request);
    }

    /**
     * 原子审批全部区段。
     *
     * @param mode NORMAL：任何正长度重叠都拒绝；PREEMPT：抢占全部冲突的、尚未激活的
     *             严格低优先级窗口后批准，无法全部抢占则整笔拒绝
     */
    @Transactional
    public RequestResponse approve(String requestId, ApprovalMode mode) {
        PossessionRequest request = requireRequest(requestId);
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new ConflictException("only PENDING requests can be approved; current="
                    + request.getStatus());
        }
        List<String> sections = repo.findSections(requestId);

        // 对全部涉及区段按固定顺序加行锁：共用区段串行，不同区段并行。
        repo.lockSections(sections.stream().sorted().toList());
        // 加锁后重读，等待期间状态可能已被其他事务改变。
        request = requireRequest(requestId);
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new ConflictException("only PENDING requests can be approved; current="
                    + request.getStatus());
        }

        List<ConflictDetail> conflicts = sortedConflicts(
                repo.findConflicts(sections, request.bufferedStart(), request.bufferedEnd(), requestId),
                request);

        if (!conflicts.isEmpty()) {
            if (mode != ApprovalMode.PREEMPT) {
                throw new ApprovalConflictException(requestId, conflicts);
            }
            preemptAll(request, conflicts);
        }

        long now = timeSource.nowMillis();
        int updated = repo.casStatus(requestId, request.getVersion(), RequestStatus.PENDING,
                RequestStatus.APPROVED, now);
        if (updated == 0) {
            throw new ConflictException("request changed concurrently; retry with new version");
        }
        appendTransitionEvent(requestId, RequestStatus.PENDING, RequestStatus.APPROVED,
                "APPROVED", null, null, now, null);

        PossessionRequest approved = requireRequest(requestId);
        approved.setSectionIds(sections);
        return RequestResponse.from(approved);
    }

    @Transactional
    public RequestResponse approve(String requestId) {
        return approve(requestId, ApprovalMode.NORMAL);
    }

    /**
     * 查询若现在审批会遇到的冲突，不改变任何状态。
     */
    public List<ConflictDetail> queryConflicts(String requestId) {
        PossessionRequest request = requireRequest(requestId);
        List<String> sections = repo.findSections(requestId);
        return sortedConflicts(
                repo.findConflicts(sections, request.bufferedStart(), request.bufferedEnd(), requestId),
                request);
    }

    @Transactional
    public RequestResponse activate(String requestId, TransitionRequest body, String requestKey) {
        return transition(requestId, body, requestKey, Operation.ACTIVATE,
                RequestStatus.APPROVED, RequestStatus.ACTIVE);
    }

    @Transactional
    public RequestResponse complete(String requestId, TransitionRequest body, String requestKey) {
        return transition(requestId, body, requestKey, Operation.COMPLETE,
                RequestStatus.ACTIVE, RequestStatus.COMPLETED);
    }

    @Transactional
    public RequestResponse cancel(String requestId, TransitionRequest body, String requestKey) {
        return transition(requestId, body, requestKey, Operation.CANCEL,
                null, RequestStatus.CANCELLED);
    }

    @Transactional
    public SnapshotResponse publishSnapshot(String requestId, String snapshotId) {
        PossessionRequest request = requireRequest(requestId);
        List<String> sections = repo.findSections(requestId);
        long now = timeSource.nowMillis();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", request.getRequestId());
        content.put("sectionIds", sections);
        content.put("start", request.startInstant().toString());
        content.put("end", request.endInstant().toString());
        content.put("bufferBeforeMs", request.getBufferBeforeMs());
        content.put("bufferAfterMs", request.getBufferAfterMs());
        content.put("bufferedStart", InstantUtil.toString(request.bufferedStart()));
        content.put("bufferedEnd", InstantUtil.toString(request.bufferedEnd()));
        content.put("priority", request.getPriority());
        content.put("version", request.getVersion());
        content.put("status", request.getStatus().name());
        List<Map<String, Object>> eventViews = new ArrayList<>();
        for (RequestEvent e : repo.findEvents(requestId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("eventType", e.getEventType());
            view.put("fromStatus", e.getFromStatus() == null ? null : e.getFromStatus().name());
            view.put("toStatus", e.getToStatus().name());
            view.put("businessTime", e.getBusinessTime() == null
                    ? null : InstantUtil.toString(e.getBusinessTime()));
            view.put("occurredAt", InstantUtil.toString(e.getOccurredAt()));
            eventViews.add(view);
        }
        content.put("events", eventViews);
        content.put("capturedAt", InstantUtil.toString(now));

        PlanSnapshot snapshot = new PlanSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setRequestId(requestId);
        snapshot.setVersionAtPublish(request.getVersion());
        snapshot.setStatusAtPublish(request.getStatus());
        snapshot.setContent(writeJson(content));
        snapshot.setPublishedAt(now);
        try {
            repo.insertSnapshot(snapshot);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("snapshotId already exists: " + snapshotId);
        }
        return SnapshotResponse.from(snapshot, readJson(snapshot.getContent()));
    }

    public SnapshotResponse getSnapshot(String snapshotId) {
        PlanSnapshot snapshot = repo.findSnapshot(snapshotId);
        if (snapshot == null) {
            throw new NotFoundException("snapshot not found: " + snapshotId);
        }
        return SnapshotResponse.from(snapshot, readJson(snapshot.getContent()));
    }

    // ---- 内部实现 ----------------------------------------------------------

    /**
     * 抢占模式：所有冲突请求必须是尚未激活（APPROVED）且优先级严格更低，
     * 否则整笔拒绝（事务回滚，不会留下部分抢占）。
     */
    private void preemptAll(PossessionRequest emergency, List<ConflictDetail> conflicts) {
        Map<String, PossessionRequest> distinct = new LinkedHashMap<>();
        for (ConflictDetail c : conflicts) {
            distinct.computeIfAbsent(c.getOtherRequestId(), repo::findRequest);
        }
        for (ConflictDetail c : conflicts) {
            if (c.getOtherStatus() == RequestStatus.ACTIVE) {
                throw new ConflictException("cannot preempt ACTIVE window " + c.getOtherRequestId()
                        + " on section " + c.getSectionId());
            }
            if (c.getOtherPriority() >= emergency.getPriority()) {
                throw new ConflictException("cannot preempt same-or-higher priority window "
                        + c.getOtherRequestId() + " on section " + c.getSectionId());
            }
        }
        long now = timeSource.nowMillis();
        for (PossessionRequest victim : distinct.values()) {
            int rows = repo.preemptRequest(victim.getRequestId(), victim.getVersion(), now);
            if (rows == 0) {
                throw new ConflictException("conflicting window changed concurrently: "
                        + victim.getRequestId());
            }
            String detail = writeJson(Map.of("preemptedBy", emergency.getRequestId()));
            appendTransitionEvent(victim.getRequestId(), RequestStatus.APPROVED,
                    RequestStatus.PREEMPTED, "PREEMPTED", null, null, now, detail);
        }
    }

    private RequestResponse transition(String requestId, TransitionRequest body, String requestKey,
                                       Operation operation, RequestStatus requiredStatus,
                                       RequestStatus targetStatus) {
        String fingerprint = Fingerprints.sha256(
                operation.name(),
                requestId,
                String.valueOf(body.expectedVersion()),
                String.valueOf(body.businessTime().toEpochMilli()));

        if (requestKey != null && !requestKey.isBlank()) {
            IdempotencyRecord existing = repo.findIdempotency(requestKey);
            if (existing != null) {
                if (!existing.getFingerprint().equals(fingerprint)) {
                    throw new ConflictException(
                            "Idempotency-Key reused with different parameters");
                }
                return readResponse(existing.getResponseBody());
            }
        }

        PossessionRequest request = requireRequest(requestId);
        List<String> sections = repo.findSections(requestId);

        // 已处于目标终态：同参重复操作返回首次结果且不追加事件；异参一律 409。
        if (request.getStatus() == targetStatus) {
            RequestEvent lastSame = findLastEvent(requestId, operation.eventType());
            if (lastSame != null && fingerprint.equals(lastSame.getFingerprint())) {
                RequestResponse replay = RequestResponse.from(request);
                rememberIdempotency(requestKey, fingerprint, operation, requestId, replay);
                return replay;
            }
            throw new ConflictException("request is already " + targetStatus
                    + " with different parameters");
        }

        if (request.getVersion() != body.expectedVersion()) {
            throw new ConflictException("version conflict: expected=" + body.expectedVersion()
                    + " actual=" + request.getVersion());
        }
        if (!isLegalMigration(operation, request.getStatus())) {
            throw new ConflictException("illegal migration " + request.getStatus() + " -> "
                    + targetStatus + " via " + operation);
        }

        long now = timeSource.nowMillis();
        int updated = repo.casStatus(requestId, body.expectedVersion(), request.getStatus(),
                targetStatus, now);
        if (updated == 0) {
            throw new ConflictException("request changed concurrently; retry with new version");
        }
        appendTransitionEvent(requestId, request.getStatus(), targetStatus, operation.eventType(),
                body.businessTime().toEpochMilli(), fingerprint, now, null);

        PossessionRequest changed = requireRequest(requestId);
        changed.setSectionIds(sections);
        RequestResponse response = RequestResponse.from(changed);
        rememberIdempotency(requestKey, fingerprint, operation, requestId, response);
        return response;
    }

    private boolean isLegalMigration(Operation operation, RequestStatus current) {
        return switch (operation) {
            case ACTIVATE -> current == RequestStatus.APPROVED;
            case COMPLETE -> current == RequestStatus.ACTIVE;
            // 未激活窗口可取消；实际占用中的窗口可提前取消释放。
            case CANCEL -> current == RequestStatus.PENDING
                    || current == RequestStatus.APPROVED
                    || current == RequestStatus.ACTIVE;
        };
    }

    private void rememberIdempotency(String requestKey, String fingerprint, Operation operation,
                                     String requestId, RequestResponse response) {
        if (requestKey == null || requestKey.isBlank()) {
            return;
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestKey(requestKey);
        record.setFingerprint(fingerprint);
        record.setOperation(operation.name());
        record.setRequestId(requestId);
        record.setResponseCode(200);
        record.setResponseBody(writeJson(response));
        record.setCreatedAt(timeSource.nowMillis());
        try {
            repo.insertIdempotency(record);
        } catch (DuplicateKeyException e) {
            // 并发同键请求：以已落库的首次结果为准。
            IdempotencyRecord winner = repo.findIdempotency(requestKey);
            if (winner == null || !winner.getFingerprint().equals(fingerprint)) {
                throw new ConflictException("Idempotency-Key reused with different parameters");
            }
        }
    }

    private RequestEvent findLastEvent(String requestId, String eventType) {
        RequestEvent match = null;
        for (RequestEvent e : repo.findEvents(requestId)) {
            if (eventType.equals(e.getEventType())) {
                match = e;
            }
        }
        return match;
    }

    private void appendTransitionEvent(String requestId, RequestStatus from, RequestStatus to,
                                       String eventType, Long businessTime, String fingerprint,
                                       long occurredAt, String detail) {
        RequestEvent event = new RequestEvent();
        event.setRequestId(requestId);
        event.setEventType(eventType);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setBusinessTime(businessTime);
        event.setOccurredAt(occurredAt);
        event.setFingerprint(fingerprint);
        event.setDetail(detail);
        repo.appendEvent(event);
    }

    private PossessionRequest requireRequest(String requestId) {
        PossessionRequest request = repo.findRequest(requestId);
        if (request == null) {
            throw new NotFoundException("request not found: " + requestId);
        }
        return request;
    }

    /**
     * 计算每条冲突的正长度重叠毫秒数，并按区段、对方占用窗开始时间、申请 ID 排序。
     */
    private List<ConflictDetail> sortedConflicts(List<ConflictDetail> rows,
                                                 PossessionRequest request) {
        long start = request.bufferedStart();
        long end = request.bufferedEnd();
        for (ConflictDetail c : rows) {
            long overlap = Math.min(end, c.getOtherBufferedEnd())
                    - Math.max(start, c.getOtherBufferedStart());
            c.setOverlapMs(Math.max(overlap, 0));
        }
        return rows.stream()
                .sorted(Comparator.comparing(ConflictDetail::getSectionId)
                        .thenComparing(ConflictDetail::getOtherBufferedStart)
                        .thenComparing(ConflictDetail::getOtherRequestId))
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse stored json", e);
        }
    }

    private RequestResponse readResponse(String json) {
        try {
            return objectMapper.readValue(json, RequestResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse stored response", e);
        }
    }

    /** 审批模式。 */
    public enum ApprovalMode {
        /** 遇冲突即整笔拒绝。 */
        NORMAL,
        /** 紧急抢占：全部冲突窗口可抢占时才批准，否则整笔拒绝。 */
        PREEMPT
    }

    private enum Operation {
        ACTIVATE("ACTIVATED"),
        COMPLETE("COMPLETED"),
        CANCEL("CANCELLED");

        private final String eventType;

        Operation(String eventType) {
            this.eventType = eventType;
        }

        String eventType() {
            return eventType;
        }
    }
}
