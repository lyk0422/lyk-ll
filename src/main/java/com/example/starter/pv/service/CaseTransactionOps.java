package com.example.starter.pv.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.pv.data.BatchItemPo;
import com.example.starter.pv.data.BatchPo;
import com.example.starter.pv.data.BatchRepository;
import com.example.starter.pv.data.CasePo;
import com.example.starter.pv.data.CaseRepository;
import com.example.starter.pv.data.FollowUpPo;
import com.example.starter.pv.data.FollowUpRepository;
import com.example.starter.pv.data.SubmissionPo;
import com.example.starter.pv.data.SubmissionRepository;
import com.example.starter.pv.domain.CaseStatus;
import com.example.starter.pv.web.BatchResponse;
import com.example.starter.pv.web.CaseView;
import com.example.starter.pv.web.CloseRequest;
import com.example.starter.pv.web.CloseResponse;
import com.example.starter.pv.web.FollowUpRequest;
import com.example.starter.pv.web.FollowUpResponse;
import com.example.starter.pv.web.InitialReportRequest;
import com.example.starter.pv.web.InitialReportResponse;
import com.example.starter.pv.web.SubmitBatchRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个例业务事务操作。每个写方法为单事务：先以 requestId 插入“占位”幂等记录占住幂等键，
 * 再行锁目标个例并完成业务，最后回填首次响应 JSON；任何异常回滚，占位记录随之回滚。
 * 时间一律按 UTC 解释，时限判定与批次内容均可复算。
 */
@Component
public class CaseTransactionOps {

    private static final String KIND_INITIAL = "INITIAL";
    private static final String KIND_FOLLOW_UP = "FOLLOW_UP";

    private final Clock clock;
    private final CaseRepository caseRepository;
    private final FollowUpRepository followUpRepository;
    private final SubmissionRepository submissionRepository;
    private final BatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    public CaseTransactionOps(Clock clock, CaseRepository caseRepository,
                              FollowUpRepository followUpRepository,
                              SubmissionRepository submissionRepository,
                              BatchRepository batchRepository,
                              ObjectMapper objectMapper) {
        this.clock = clock;
        this.caseRepository = caseRepository;
        this.followUpRepository = followUpRepository;
        this.submissionRepository = submissionRepository;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 注入的 UTC 时钟。
     */
    public Clock clock() {
        return clock;
    }

    /**
     * 初报事务：活动个例重复则并入；最近同组合个例已关闭则新建关联个例引用原编号。
     */
    @Transactional
    public InitialReportResponse submitInitialTx(InitialReportRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(List.of(
                KIND_INITIAL, req.requestId(), req.patientCode(), req.patientName(),
                req.drugCode(), req.drugName(), req.reactionEvent(), req.seriousness().name(),
                req.awarenessDate().toString(), req.submittedAt().toString(),
                req.lateReason() == null ? "" : req.lateReason().trim()));
        reserve(req.requestId(), KIND_INITIAL, fingerprint, now, InitialReportResponse.class);

        CasePo candidate = caseRepository
                .findActive(req.patientCode(), req.drugCode(), req.reactionEvent())
                .map(c -> caseRepository.lockByCaseNo(c.caseNo()).orElseThrow())
                .orElse(null);

        InitialReportResponse response;
        if (candidate != null && candidate.status() == CaseStatus.REPORTED) {
            response = mergeIntoActive(candidate, req, now);
        } else {
            LocalDate dueDate = DeadlineRules.dueDate(req.seriousness(), req.awarenessDate());
            boolean onTime = DeadlineRules.isOnTime(dueDate, req.submittedAt());
            String lateReason = req.lateReason() == null ? null : req.lateReason().trim();
            if (!onTime && (lateReason == null || lateReason.isEmpty())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "初报已迟报，必须填写迟报原因");
            }
            if (onTime && lateReason != null && !lateReason.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "初报及时，不得填写迟报原因");
            }
            Instant awarenessStart = req.awarenessDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            if (req.submittedAt().isBefore(awarenessStart)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "提交时刻不得早于获知日 T0");
            }
            String relatedCaseNo;
            if (candidate != null) {
                relatedCaseNo = candidate.caseNo();
            } else {
                relatedCaseNo = caseRepository
                        .findLatestByDedup(req.patientCode(), req.drugCode(), req.reactionEvent())
                        .filter(c -> c.status() == CaseStatus.CLOSED)
                        .map(CasePo::caseNo)
                        .orElse(null);
            }
            response = createNewCase(req, dueDate, onTime, lateReason, relatedCaseNo, now);
        }
        submissionRepository.complete(req.requestId(), response.caseNo(),
                response.version(), response.merged(), writeJson(response));
        return response;
    }

    /**
     * 随访事务：刷新最近随访日并产生新版本；关闭终态拒绝随访。
     */
    @Transactional
    public FollowUpResponse submitFollowUpTx(String caseNo, FollowUpRequest req) {
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(List.of(
                KIND_FOLLOW_UP, req.requestId(), caseNo, req.followUpDate().toString(),
                req.info(), req.reporterCode(),
                req.reporterName() == null ? "" : req.reporterName()));
        reserve(req.requestId(), KIND_FOLLOW_UP, fingerprint, now, FollowUpResponse.class);

        CasePo current = caseRepository.lockByCaseNo(caseNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "个例不存在: " + caseNo));
        if (current.status() == CaseStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "个例已关闭（终态），不得新增随访；请新建关联个例并引用原编号 " + caseNo);
        }
        LocalDate today = LocalDate.now(clock);
        if (req.followUpDate().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "随访信息日不得晚于当前 UTC 日期");
        }

        int newCount = current.followUpCount() + 1;
        int version = newCount + 1;
        followUpRepository.insert(new FollowUpPo(null, caseNo, version, req.followUpDate(),
                req.info(), req.reporterCode(), req.reporterName(), req.requestId(), now));
        caseRepository.applyFollowUp(caseNo, req.followUpDate(), newCount, now);
        CasePo refreshed = caseRepository.lockByCaseNo(caseNo).orElseThrow();

        FollowUpResponse response = new FollowUpResponse(caseNo, version,
                refreshed.latestFollowUpDate(), toView(refreshed));
        submissionRepository.complete(req.requestId(), caseNo, version, false, writeJson(response));
        return response;
    }

    /**
     * 关闭事务：末次信息（无随访则为初报日）后满 30 日方可；重复关闭幂等成功。
     */
    @Transactional
    public CloseResponse closeTx(String caseNo, CloseRequest request) {
        CasePo current = caseRepository.lockByCaseNo(caseNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "个例不存在: " + caseNo));
        if (current.status() == CaseStatus.CLOSED) {
            return new CloseResponse(caseNo, CaseStatus.CLOSED.name(), true, toView(current));
        }
        LocalDate baseline = current.latestFollowUpDate() != null
                ? current.latestFollowUpDate()
                : current.initialSubmittedAt().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate today = LocalDate.now(clock);
        if (!DeadlineRules.isCloseable(baseline, today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "距末次信息未满 30 日，最早可申请关闭日（UTC）: "
                            + DeadlineRules.closeEarliestDate(baseline));
        }
        Instant now = Instant.now(clock);
        caseRepository.markClosed(caseNo, now);
        CasePo refreshed = caseRepository.lockByCaseNo(caseNo).orElseThrow();
        return new CloseResponse(caseNo, CaseStatus.CLOSED.name(), false, toView(refreshed));
    }

    /**
     * 递交事务：生成唯一批次号，行锁快照提交时刻已确认个例，内容此后固定。
     */
    @Transactional
    public BatchResponse submitBatchTx(SubmitBatchRequest request) {
        Instant now = Instant.now(clock);
        List<String> requested = normalizeCaseNos(request.caseNos());
        String fingerprint = Fingerprints.of(List.of(
                "BATCH", request.requestId(), String.join(",", requested)));

        BatchPo existing = batchRepository.findBatchByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "同一 requestId 已用于不同批次内容，拒绝产生新批次");
            }
            return assembleBatch(existing);
        }

        List<CasePo> snapshotCases = requested.isEmpty()
                ? caseRepository.lockActiveAll()
                : lockRequestedActiveCases(requested);

        String batchNo = DeadlineRules.newBatchNo(now);
        try {
            batchRepository.insertBatch(new BatchPo(null, batchNo, request.requestId(),
                    fingerprint, snapshotCases.size(), now));
        } catch (DuplicateKeyException ex) {
            BatchPo raced = batchRepository.findBatchByRequestId(request.requestId())
                    .orElseThrow(() -> ex);
            if (!raced.requestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "同一 requestId 已用于不同批次内容，拒绝产生新批次");
            }
            return assembleBatch(raced);
        }

        List<BatchResponse.BatchItemView> items = new ArrayList<>();
        int ordinal = 0;
        for (CasePo po : snapshotCases) {
            CaseView view = toView(po);
            batchRepository.insertItem(new BatchItemPo(null, batchNo, po.caseNo(),
                    po.followUpCount() + 1, writeJson(view), ordinal++));
            items.add(new BatchResponse.BatchItemView(po.caseNo(), po.followUpCount() + 1, view));
        }
        return new BatchResponse(batchNo, items.size(), now, items);
    }

    /**
     * 查询个例详情。
     */
    @Transactional(readOnly = true)
    public CaseView getCase(String caseNo) {
        return toView(caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "个例不存在: " + caseNo)));
    }

    /**
     * 查询全部个例（按编号升序）。
     */
    @Transactional(readOnly = true)
    public List<CaseView> listCases() {
        return caseRepository.findAll().stream().map(this::toView).toList();
    }

    /**
     * 按批次号查询批次及其固定快照。
     */
    @Transactional(readOnly = true)
    public BatchResponse getBatch(String batchNo) {
        return assembleBatch(batchRepository.findBatchByNo(batchNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "批次不存在: " + batchNo)));
    }

    // ------------------------------------------------------------------
    // 作用域末尾的内部辅助方法
    // ------------------------------------------------------------------

    private InitialReportResponse mergeIntoActive(CasePo active, InitialReportRequest req,
                                                  Instant now) {
        int newCount = active.followUpCount() + 1;
        int version = newCount + 1;
        String info = "重复初报并入：" + req.drugName() + " / " + req.reactionEvent()
                + "（严重程度 " + req.seriousness() + "）";
        followUpRepository.insert(new FollowUpPo(null, active.caseNo(), version,
                req.awarenessDate(), info, null, null, req.requestId(), now));
        caseRepository.applyFollowUp(active.caseNo(), req.awarenessDate(), newCount, now);
        CasePo refreshed = caseRepository.lockByCaseNo(active.caseNo()).orElseThrow();
        CaseView view = toView(refreshed);
        return new InitialReportResponse(active.caseNo(), version, true, view.onTime(), view);
    }

    private InitialReportResponse createNewCase(InitialReportRequest req, LocalDate dueDate,
                                                boolean onTime, String lateReason,
                                                String relatedCaseNo, Instant now) {
        String caseNo = DeadlineRules.newCaseNo(now);
        CasePo po = new CasePo(null, caseNo, req.patientCode(), req.patientName(),
                req.drugCode(), req.drugName(), req.reactionEvent(), req.seriousness(),
                CaseStatus.REPORTED, req.awarenessDate(), req.submittedAt(), dueDate, onTime,
                onTime ? null : lateReason, null, 0, relatedCaseNo, now, now);
        caseRepository.insert(po);
        return new InitialReportResponse(caseNo, 1, false, onTime, toView(po));
    }

    private List<CasePo> lockRequestedActiveCases(List<String> requested) {
        List<CasePo> found = caseRepository.lockActiveByCaseNos(requested);
        if (found.size() != requested.size()) {
            List<String> foundNos = found.stream().map(CasePo::caseNo).toList();
            List<String> missing = requested.stream()
                    .filter(n -> !foundNos.contains(n)).toList();
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "以下个例不存在或已关闭，不能纳入批次: " + missing);
        }
        return found;
    }

    private void reserve(String requestId, String kind, String fingerprint, Instant now,
                         Class<?> responseType) {
        try {
            submissionRepository.insert(new SubmissionPo(null, requestId, kind, fingerprint,
                    null, null, false, null, now));
        } catch (DuplicateKeyException ex) {
            SubmissionPo existing = submissionRepository.findByRequestId(requestId)
                    .orElseThrow(() -> ex);
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "requestId 已存在但业务参数不一致，拒绝执行（不产生新版本）");
            }
            if (existing.responseJson() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "相同请求正在处理中，请稍后重试");
            }
            throw new IdempotentReplayException(readJson(existing.responseJson(), responseType));
        }
    }

    private List<String> normalizeCaseNos(List<String> caseNos) {
        if (caseNos == null) {
            return List.of();
        }
        return caseNos.stream().filter(n -> n != null && !n.isBlank()).distinct().sorted().toList();
    }

    private BatchResponse assembleBatch(BatchPo header) {
        List<BatchResponse.BatchItemView> items = batchRepository.findItems(header.batchNo())
                .stream()
                .map(item -> new BatchResponse.BatchItemView(item.caseNo(), item.versionNo(),
                        readJson(item.snapshotJson(), CaseView.class)))
                .toList();
        return new BatchResponse(header.batchNo(), header.caseCount(), header.createdAt(), items);
    }

    private CaseView toView(CasePo po) {
        return new CaseView(po.caseNo(), po.patientCode(), po.patientName(), po.drugCode(),
                po.drugName(), po.reactionEvent(), po.seriousness(), po.status(),
                po.awarenessDate(), po.initialSubmittedAt(), po.initialDueDate(), po.onTime(),
                po.lateReason(), po.latestFollowUpDate(), po.followUpCount(),
                po.relatedCaseNo(), po.followUpCount() + 1, po.createdAt(), po.updatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
