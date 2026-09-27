package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAssignItem;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignmentView;
import com.example.starter.pesticide.dto.ApiDtos.JointItemView;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgmentView;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultView;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 联检服务：疑似冻结批次多项目联合检测（合成规则，不用于生产）。
 * 建单冻结项目目录/限值/机构能力版本；分样覆盖全部项目且样本号全局唯一；
 * 结果恰等限值合格、复测保留旧结果递增版本；综合判定携带全部项版本并冻结证据，
 * 合格在同事务放行、不合格转原处置流程留样 48 小时；判定后结果只记补充信息。
 * 全部写操作 requestId 幂等（同键同参重放首次冻结结果，异参 409，失败不占键），
 * 变更携带 expectedVersion 乐观校验（版本从 1 起，成功变更加 1，失配零写入）。
 */
@Service
public class JointService {

    private final JointDao jointDao;
    private final BatchDao batchDao;
    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JointService(JointDao jointDao, BatchDao batchDao, TestDao testDao, LedgerDao ledgerDao,
                        ObjectMapper objectMapper, Clock clock) {
        this.jointDao = jointDao;
        this.batchDao = batchDao;
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 建立联检单：批次须疑似冻结，项目至少两种且唯一，逐项限值与机构能力版本一并冻结。
     */
    @Transactional
    public JointOrderResponse createOrder(JointOrderCreateRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.requestId());
        parts.add(String.valueOf(request.batchId()));
        parts.add(request.labCode());
        parts.add(request.occurredAt().toString());
        request.itemCodes().stream().sorted().forEach(parts::add);
        String fingerprint = Fingerprint.structured(parts.toArray(new String[0]));
        return runIdempotent("CREATE_ORDER", request.requestId(), fingerprint, JointOrderResponse.class,
                r -> new JointOrderResponse(r.orderId(), r.requestId(), r.batchId(), r.labCode(),
                        r.labName(), r.labCapabilityVersion(), r.catalogVersion(), r.items(), r.status(),
                        r.version(), r.createdAt(), true),
                () -> doCreateOrder(request, fingerprint));
    }

    /**
     * 分样分配：一次覆盖联检单全部项目，样本号全局唯一，任一失败整单回滚不分样。
     */
    @Transactional
    public JointAssignResponse assign(long orderId, JointAssignRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.requestId());
        parts.add(String.valueOf(orderId));
        parts.add(String.valueOf(request.expectedVersion()));
        parts.add(request.occurredAt().toString());
        request.items().stream()
                .sorted(Comparator.comparing(JointAssignItem::itemCode))
                .forEach(i -> {
                    parts.add(i.itemCode());
                    parts.add(i.sampleNo());
                });
        String fingerprint = Fingerprint.structured(parts.toArray(new String[0]));
        return runIdempotent("ASSIGN", request.requestId(), fingerprint, JointAssignResponse.class,
                r -> new JointAssignResponse(r.orderId(), r.status(), r.version(), r.assignments(), true),
                () -> doAssign(orderId, request, fingerprint));
    }

    /**
     * 提交/复测联检结果：恰等限值合格；复测保留旧结果并递增项目版本；
     * 综合判定后只记补充信息不重开。
     */
    @Transactional
    public JointResultResponse submitResult(long orderId, JointResultRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.itemCode(),
                normalizeDecimal(request.concentration()), request.occurredAt().toString());
        return runIdempotent("RESULT", request.requestId(), fingerprint, JointResultResponse.class,
                r -> new JointResultResponse(r.resultId(), r.orderId(), r.itemCode(), r.sampleNo(),
                        r.concentration(), r.qualified(), r.itemVersion(), r.orderVersion(), r.status(),
                        r.supplemental(), true),
                () -> doSubmitResult(orderId, request, fingerprint));
    }

    /**
     * 综合判定：全部项目有当前结果方可判定（尚缺一项只能待定）；任一超限整体不合格；
     * 携带全部项版本并冻结证据；合格同事务放行、不合格转原处置流程留样 48 小时。
     */
    @Transactional
    public JointJudgeResponse judge(long orderId, JointJudgeRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.occurredAt().toString());
        return runIdempotent("JUDGE", request.requestId(), fingerprint, JointJudgeResponse.class,
                r -> new JointJudgeResponse(r.judgmentId(), r.orderId(), r.verdict(), r.itemVersions(),
                        r.version(), r.batchId(), r.batchStatus(), r.retentionUntil(), r.judgedAt(), true),
                () -> doJudge(orderId, request, fingerprint));
    }

    /**
     * 判定后补充信息：只记录不重开判定、不改批次状态。
     */
    @Transactional
    public JointSupplementResponse supplement(long orderId, JointSupplementRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                request.itemCode() == null ? "" : request.itemCode(), request.note(),
                request.occurredAt().toString());
        return runIdempotent("SUPPLEMENT", request.requestId(), fingerprint, JointSupplementResponse.class,
                r -> new JointSupplementResponse(r.supplementId(), r.orderId(), true),
                () -> doSupplement(orderId, request, fingerprint));
    }

    /**
     * 联检单详情：含冻结项目、分样、全部结果版本、综合判定与补充信息。
     */
    @Transactional(readOnly = true)
    public JointOrderDetailResponse detail(long orderId) {
        JointDao.OrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        return toDetail(order);
    }

    /**
     * 按批次查询联检单详情。
     */
    @Transactional(readOnly = true)
    public JointOrderDetailResponse detailByBatch(long batchId) {
        JointDao.OrderRow order = jointDao.findOrderByBatchId(batchId)
                .orElseThrow(() -> ApiException.notFound("批次未建联检单: " + batchId));
        return toDetail(order);
    }

    // ============ 业务实现（均在幂等包装的事务内执行） ============

    private Outcome<JointOrderResponse> doCreateOrder(JointOrderCreateRequest request, String fingerprint) {
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("仅疑似冻结批次可建联检单，当前状态: " + batch.status());
        }
        if (jointDao.findOrderByBatchId(batch.id())
                .filter(o -> !o.requestId().equals(request.requestId()))
                .isPresent()) {
            throw ApiException.unprocessable("批次已建联检单，不得重复建单");
        }
        JointDao.LabRow lab = jointDao.findLabByCode(request.labCode())
                .orElseThrow(() -> ApiException.notFound("检验机构不存在: " + request.labCode()));
        if (!lab.active()) {
            throw ApiException.unprocessable("检验机构已停用: " + request.labCode());
        }
        if (new HashSet<>(request.itemCodes()).size() != request.itemCodes().size()) {
            throw ApiException.badRequest("联检项目必须唯一，存在重复项目编码");
        }
        List<JointDao.CatalogItemRow> catalogItems = new ArrayList<>();
        for (String itemCode : request.itemCodes()) {
            JointDao.CatalogItemRow item = jointDao.findCatalogItem(itemCode)
                    .orElseThrow(() -> ApiException.notFound("检测项目不在合成目录: " + itemCode));
            if (!item.active()) {
                throw ApiException.unprocessable("检测项目已停用: " + itemCode);
            }
            if (!jointDao.labHasCapability(lab.id(), itemCode)) {
                throw ApiException.unprocessable(
                        "机构不具备项目能力: " + request.labCode() + "/" + itemCode);
            }
            catalogItems.add(item);
        }
        String catalogVersion = catalogItems.get(0).catalogVersion();
        String frozenTests = testDao.findTestsByBatch(batch.id()).stream()
                .map(t -> t.item() + "=" + t.inhibitionRate().toPlainString() + ":" + t.result())
                .collect(Collectors.joining(";"));
        LocalDateTime now = nowUtc();
        long orderId = jointDao.insertOrder(request.requestId(), fingerprint, batch.id(), lab,
                catalogVersion, frozenTests, now);
        for (JointDao.CatalogItemRow item : catalogItems) {
            jointDao.insertOrderItem(orderId, item);
        }
        List<JointItemView> items = catalogItems.stream()
                .map(i -> new JointItemView(i.itemCode(), i.itemName(), i.unit(), i.limitValue()))
                .toList();
        return new Outcome<>(orderId, new JointOrderResponse(orderId, request.requestId(), batch.id(),
                lab.labCode(), lab.labName(), lab.capabilityVersion(), catalogVersion, items,
                JointOrderStatus.CREATED.name(), 1, now.toInstant(ZoneOffset.UTC), false));
    }

    private Outcome<JointAssignResponse> doAssign(long orderId, JointAssignRequest request,
                                                  String fingerprint) {
        JointDao.OrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        if (JointOrderStatus.valueOf(order.status()) != JointOrderStatus.CREATED) {
            throw ApiException.unprocessable("联检单当前状态不允许分样: " + order.status());
        }
        requireVersion(order, request.expectedVersion());
        List<JointDao.OrderItemRow> orderItems = jointDao.findOrderItems(orderId);
        Map<String, String> sampleByItem = new LinkedHashMap<>();
        for (JointAssignItem item : request.items()) {
            if (sampleByItem.putIfAbsent(item.itemCode(), item.sampleNo()) != null) {
                throw ApiException.badRequest("分样集合存在重复项目: " + item.itemCode());
            }
        }
        Map<String, String> required = orderItems.stream().collect(Collectors.toMap(
                JointDao.OrderItemRow::itemCode, JointDao.OrderItemRow::itemCode));
        if (!sampleByItem.keySet().equals(required.keySet())) {
            throw ApiException.unprocessable("分样须覆盖联检单全部项目且不包含单外项目");
        }
        if (new HashSet<>(sampleByItem.values()).size() != sampleByItem.size()) {
            throw ApiException.badRequest("分样集合存在重复样本号");
        }
        for (String sampleNo : sampleByItem.values()) {
            if (jointDao.sampleNoExists(sampleNo)) {
                throw ApiException.unprocessable("样本号已全局使用: " + sampleNo);
            }
        }
        LocalDateTime now = nowUtc();
        if (jointDao.advanceOrder(orderId, request.expectedVersion(),
                JointOrderStatus.ASSIGNED.name(), now) == 0) {
            throw ApiException.conflict("联检单版本已变更，分样未写入");
        }
        List<JointAssignmentView> views = new ArrayList<>();
        for (JointDao.OrderItemRow orderItem : orderItems) {
            String sampleNo = sampleByItem.get(orderItem.itemCode());
            long assignmentId = jointDao.insertAssignment(request.requestId(), fingerprint, orderId,
                    orderItem.itemCode(), sampleNo, now);
            views.add(new JointAssignmentView(assignmentId, orderItem.itemCode(), sampleNo,
                    now.toInstant(ZoneOffset.UTC)));
        }
        return new Outcome<>(orderId, new JointAssignResponse(orderId, JointOrderStatus.ASSIGNED.name(),
                request.expectedVersion() + 1, views, false));
    }

    private Outcome<JointResultResponse> doSubmitResult(long orderId, JointResultRequest request,
                                                        String fingerprint) {
        JointDao.OrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        requireVersion(order, request.expectedVersion());
        JointDao.OrderItemRow orderItem = jointDao.findOrderItems(orderId).stream()
                .filter(i -> i.itemCode().equals(request.itemCode()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("项目不在联检单内: " + request.itemCode()));
        JointDao.AssignmentRow assignment = jointDao.findAssignment(orderId, request.itemCode())
                .orElseThrow(() -> ApiException.unprocessable("项目未分样: " + request.itemCode()));
        LocalDateTime now = nowUtc();
        if (JointOrderStatus.valueOf(order.status()) == JointOrderStatus.JUDGED) {
            // 已综合判定：结果只记补充信息，不重开判定、不改批次状态
            String note = "判定后结果补充: item=" + request.itemCode()
                    + ", sampleNo=" + assignment.sampleNo()
                    + ", concentration=" + request.concentration().toPlainString();
            jointDao.insertSupplement(request.requestId(), fingerprint, orderId, request.itemCode(),
                    note, now);
            return new Outcome<>(orderId, new JointResultResponse(null, orderId, request.itemCode(),
                    assignment.sampleNo(), request.concentration(), null, null, order.version(),
                    order.status(), true, false));
        }
        JointOrderStatus status = JointOrderStatus.valueOf(order.status());
        if (status != JointOrderStatus.ASSIGNED && status != JointOrderStatus.PENDING) {
            throw ApiException.unprocessable("联检单未分样，不得提交结果: " + order.status());
        }
        boolean qualified = request.concentration().compareTo(orderItem.limitValue()) <= 0;
        int itemVersion = jointDao.maxItemVersion(orderId, request.itemCode()) + 1;
        int newOrderVersion = request.expectedVersion() + 1;
        if (jointDao.advanceOrder(orderId, request.expectedVersion(),
                JointOrderStatus.PENDING.name(), now) == 0) {
            throw ApiException.conflict("联检单版本已变更，结果未写入");
        }
        jointDao.supersedeCurrentResult(orderId, request.itemCode());
        long resultId = jointDao.insertResult(request.requestId(), fingerprint, orderId, assignment.id(),
                request.itemCode(), request.concentration(), qualified, itemVersion, newOrderVersion, now);
        return new Outcome<>(orderId, new JointResultResponse(resultId, orderId, request.itemCode(),
                assignment.sampleNo(), request.concentration(), qualified, itemVersion, newOrderVersion,
                JointOrderStatus.PENDING.name(), false, false));
    }

    private Outcome<JointJudgeResponse> doJudge(long orderId, JointJudgeRequest request,
                                                String fingerprint) {
        JointDao.OrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        JointOrderStatus status = JointOrderStatus.valueOf(order.status());
        if (status == JointOrderStatus.JUDGED) {
            throw ApiException.unprocessable("联检单已综合判定，不得重复判定");
        }
        if (status == JointOrderStatus.CREATED) {
            throw ApiException.unprocessable("联检单未分样，不得综合判定");
        }
        requireVersion(order, request.expectedVersion());
        List<JointDao.OrderItemRow> items = jointDao.findOrderItems(orderId);
        Map<String, JointDao.ResultRow> currentByItem = jointDao.findCurrentResults(orderId).stream()
                .collect(Collectors.toMap(JointDao.ResultRow::itemCode, r -> r));
        if (currentByItem.size() < items.size()) {
            throw ApiException.unprocessable("尚有项目未出结果，综合判定只能待定");
        }
        boolean allQualified = items.stream().allMatch(i -> currentByItem.get(i.itemCode()).qualified());
        String verdict = allQualified ? "QUALIFIED" : "UNQUALIFIED";
        Map<String, String> sampleByItem = jointDao.findAssignments(orderId).stream()
                .collect(Collectors.toMap(JointDao.AssignmentRow::itemCode,
                        JointDao.AssignmentRow::sampleNo));
        List<JointDao.OrderItemRow> sortedItems = items.stream()
                .sorted(Comparator.comparing(JointDao.OrderItemRow::itemCode))
                .toList();
        String itemVersions = sortedItems.stream()
                .map(i -> i.itemCode() + "=" + currentByItem.get(i.itemCode()).itemVersion())
                .collect(Collectors.joining(";"));
        List<Map<String, Object>> evidenceEntries = sortedItems.stream()
                .map(i -> {
                    JointDao.ResultRow r = currentByItem.get(i.itemCode());
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("resultId", r.id());
                    entry.put("itemCode", i.itemCode());
                    entry.put("itemVersion", r.itemVersion());
                    entry.put("sampleNo", sampleByItem.get(i.itemCode()));
                    entry.put("concentration", r.concentration().toPlainString());
                    entry.put("limitValue", i.limitValue().toPlainString());
                    entry.put("qualified", r.qualified());
                    return entry;
                })
                .collect(Collectors.toList());
        String evidence = writeJson(evidenceEntries);
        String evidenceFingerprint = Fingerprint.structured(evidence);
        LocalDateTime now = nowUtc();
        if (jointDao.judgeOrder(orderId, request.expectedVersion(), now, now) == 0) {
            throw ApiException.conflict("联检单版本已变更，综合判定零写入");
        }
        long judgmentId = jointDao.insertJudgment(request.requestId(), fingerprint, orderId, verdict,
                itemVersions, evidence, evidenceFingerprint, now, now);
        BatchDao.BatchRow batch = batchDao.lockById(order.batchId()).orElseThrow();
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("批次状态异常，无法完成综合判定: " + batch.status());
        }
        String batchStatus;
        Instant retentionUntil = null;
        if (allQualified) {
            ledgerDao.insertAdmission(batch.id(), now);
            batchDao.updateStatus(batch.id(), BatchStatus.RELEASED.name(), now);
            batchStatus = BatchStatus.RELEASED.name();
        } else {
            ledgerDao.insertDisposal(batch.id(), "联检综合判定不合格，转监督处置", now);
            LocalDateTime until = now.plusHours(TestService.RETENTION_HOURS);
            ledgerDao.insertRetention(batch.id(), TestService.RETENTION_HOURS, until, now);
            batchDao.updateStatus(batch.id(), BatchStatus.DISPOSED.name(), now);
            batchStatus = BatchStatus.DISPOSED.name();
            retentionUntil = until.toInstant(ZoneOffset.UTC);
        }
        return new Outcome<>(orderId, new JointJudgeResponse(judgmentId, orderId, verdict, itemVersions,
                request.expectedVersion() + 1, batch.id(), batchStatus, retentionUntil,
                now.toInstant(ZoneOffset.UTC), false));
    }

    private Outcome<JointSupplementResponse> doSupplement(long orderId, JointSupplementRequest request,
                                                          String fingerprint) {
        JointDao.OrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        if (JointOrderStatus.valueOf(order.status()) != JointOrderStatus.JUDGED) {
            throw ApiException.unprocessable("联检单未综合判定，不得补充: " + order.status());
        }
        long supplementId = jointDao.insertSupplement(request.requestId(), fingerprint, orderId,
                request.itemCode(), request.note(), nowUtc());
        return new Outcome<>(orderId, new JointSupplementResponse(supplementId, orderId, false));
    }

    // ============ 幂等与装配 ============

    /**
     * 幂等执行：同键同参重放首次冻结结果，异参 409；业务失败抛异常回滚不占键；
     * 幂等记录与业务写入同事务提交；并发下同键请求在唯一约束或版本冲突后重放首次结果。
     */
    private <T> T runIdempotent(String operation, String requestId, String fingerprint,
                                Class<T> type, Function<T, T> replayMarker,
                                Supplier<Outcome<T>> business) {
        var existing = jointDao.findIdempotency(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint, type, replayMarker);
        }
        Outcome<T> outcome;
        try {
            outcome = business.get();
            jointDao.insertIdempotency(requestId, fingerprint, operation, outcome.orderId(),
                    writeJson(outcome.response()), nowUtc());
        } catch (DuplicateKeyException | ApiException e) {
            var raced = jointDao.findIdempotency(requestId);
            if (raced.isPresent()) {
                return replayOrConflict(raced.get(), fingerprint, type, replayMarker);
            }
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw ApiException.conflict("requestId 或业务唯一键冲突");
        }
        return outcome.response();
    }

    private <T> T replayOrConflict(JointDao.IdempotencyRow row, String fingerprint,
                                   Class<T> type, Function<T, T> replayMarker) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已使用且业务字段不一致");
        }
        return replayMarker.apply(readResponse(row.responseJson(), type));
    }

    private void requireVersion(JointDao.OrderRow order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("联检单版本已变更，期望版本 " + expectedVersion
                    + " 与当前版本 " + order.version() + " 不一致");
        }
    }

    private JointOrderDetailResponse toDetail(JointDao.OrderRow order) {
        long orderId = order.id();
        List<JointItemView> items = jointDao.findOrderItems(orderId).stream()
                .map(i -> new JointItemView(i.itemCode(), i.itemName(), i.unit(), i.limitValue()))
                .toList();
        List<JointDao.AssignmentRow> assignmentRows = jointDao.findAssignments(orderId);
        List<JointAssignmentView> assignments = assignmentRows.stream()
                .map(a -> new JointAssignmentView(a.id(), a.itemCode(), a.sampleNo(),
                        a.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        Map<Long, String> sampleByAssignmentId = assignmentRows.stream()
                .collect(Collectors.toMap(JointDao.AssignmentRow::id, JointDao.AssignmentRow::sampleNo));
        List<JointResultView> results = jointDao.findResults(orderId).stream()
                .map(r -> new JointResultView(r.id(), r.itemCode(),
                        sampleByAssignmentId.get(r.assignmentId()), r.concentration(), r.qualified(),
                        r.itemVersion(), r.orderVersion(), r.superseded(),
                        r.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        JointJudgmentView judgment = jointDao.findJudgmentByOrderId(orderId)
                .map(j -> new JointJudgmentView(j.id(), j.verdict(), j.itemVersions(), j.evidence(),
                        j.evidenceFingerprint(), j.judgedAt().toInstant(ZoneOffset.UTC)))
                .orElse(null);
        List<JointSupplementView> supplements = jointDao.findSupplements(orderId).stream()
                .map(s -> new JointSupplementView(s.id(), s.itemCode(), s.note(),
                        s.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        return new JointOrderDetailResponse(order.id(), order.batchId(), order.labCode(), order.labName(),
                order.labCapabilityVersion(), order.catalogVersion(), order.frozenTests(), order.status(),
                order.version(),
                order.judgedAt() == null ? null : order.judgedAt().toInstant(ZoneOffset.UTC),
                order.createdAt().toInstant(ZoneOffset.UTC), items, assignments, results, judgment,
                supplements);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readResponse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private static String normalizeDecimal(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 幂等执行结果：关联联检单 ID 与冻结响应。
     */
    private record Outcome<T>(long orderId, T response) {
    }
}
