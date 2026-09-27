package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.JointDtos.AgencyView;
import com.example.starter.pesticide.dto.JointDtos.AssignmentItem;
import com.example.starter.pesticide.dto.JointDtos.AssignmentView;
import com.example.starter.pesticide.dto.JointDtos.CatalogItemView;
import com.example.starter.pesticide.dto.JointDtos.CatalogResponse;
import com.example.starter.pesticide.dto.JointDtos.DeterminationItemView;
import com.example.starter.pesticide.dto.JointDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.JointDtos.JointAssignResponse;
import com.example.starter.pesticide.dto.JointDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.JointDtos.JointCreateResponse;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineRequest;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineResponse;
import com.example.starter.pesticide.dto.JointDtos.JointItemView;
import com.example.starter.pesticide.dto.JointDtos.JointOrderResponse;
import com.example.starter.pesticide.dto.JointDtos.JointResultRequest;
import com.example.starter.pesticide.dto.JointDtos.JointResultResponse;
import com.example.starter.pesticide.dto.JointDtos.JointResultView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 联检服务：疑似冻结批次建立联检单（引用冻结快检结果与合成项目目录），
 * 分样覆盖全部项目，逐项结果恰等限值合格、任一超限整体不合格；
 * 复测仅在综合判定前替换当前结果并保留旧版本；综合判定携带全部项版本冻结证据，
 * 合格同事务放行、不合格同事务转处置留样。全部规则为合成规则。
 */
@Service
public class JointService {

    /** 联检单状态：已建单待分样 */
    public static final String STATUS_CREATED = "CREATED";
    /** 联检单状态：已分样待判定 */
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    /** 联检单状态：已综合判定（终态） */
    public static final String STATUS_DETERMINED = "DETERMINED";
    /** 综合判定结果：合格 */
    public static final String RESULT_PASS = "PASS";
    /** 综合判定结果：不合格 */
    public static final String RESULT_FAIL = "FAIL";

    private final JointDao jointDao;
    private final BatchDao batchDao;
    private final TestDao testDao;
    private final LedgerDao ledgerDao;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JointService(JointDao jointDao, BatchDao batchDao, TestDao testDao,
                        LedgerDao ledgerDao, ObjectMapper objectMapper, Clock clock) {
        this.jointDao = jointDao;
        this.batchDao = batchDao;
        this.testDao = testDao;
        this.ledgerDao = ledgerDao;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 联检建单 ----------

    /**
     * 建单：requestId 幂等（同键同参重放首次冻结结果，异参 409，失败不占键）；
     * 批次须疑似冻结且引用本批次的疑似快检记录；项目至少两种且唯一，
     * 逐项限值、目录版本与机构能力目录版本一并冻结。
     */
    @Transactional
    public JointCreateResponse create(JointCreateRequest request) {
        List<String> items = request.items().stream().map(String::trim).toList();
        if (items.size() < 2) {
            throw ApiException.badRequest("联检项目至少两种");
        }
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("联检项目必须唯一，存在重复项目");
        }
        String fingerprint = Fingerprint.structured(joinParts("JOINT_CREATE",
                request.requestId(), String.valueOf(request.batchId()),
                String.valueOf(request.sourceTestId()), sorted(items)));
        JointCreateResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                JointCreateResponse.class);
        if (replayed != null) {
            return replayed;
        }
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        // 行锁后复查：并发同键请求在此串行化，后到者重放首次冻结结果
        JointCreateResponse racedReplay = replayIfPresent(request.requestId(), fingerprint,
                JointCreateResponse.class);
        if (racedReplay != null) {
            return racedReplay;
        }
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("仅疑似冻结批次可建联检单，当前状态: " + batch.status());
        }
        TestDao.TestRow sourceTest = testDao.findTestById(request.sourceTestId())
                .orElseThrow(() -> ApiException.notFound("快检记录不存在: " + request.sourceTestId()));
        if (sourceTest.batchId() != batch.id() || !"SUSPECT".equals(sourceTest.result())) {
            throw ApiException.unprocessable("引用的快检记录非本批次疑似冻结结果");
        }
        Map<String, JointDao.CatalogItemRow> catalog = items.stream()
                .map(item -> jointDao.findCatalogItem(item)
                        .orElseThrow(() -> ApiException.unprocessable("项目不在合成项目目录: " + item)))
                .collect(Collectors.toMap(JointDao.CatalogItemRow::item, Function.identity()));
        String capabilityVersion = jointDao.capabilityDirectoryVersion();
        LocalDateTime now = nowUtc();
        long orderId;
        try {
            orderId = jointDao.insertOrder(batch.id(), sourceTest.id(), STATUS_CREATED,
                    capabilityVersion, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("批次已建联检单，每批次至多一张");
        }
        List<JointItemView> itemViews = new ArrayList<>();
        for (String item : items) {
            JointDao.CatalogItemRow cat = catalog.get(item);
            jointDao.insertItem(orderId, item, cat.limitValue(), cat.catalogVersion());
            itemViews.add(new JointItemView(item, cat.limitValue(), cat.catalogVersion()));
        }
        JointCreateResponse response = new JointCreateResponse(orderId, request.requestId(),
                batch.id(), sourceTest.id(), STATUS_CREATED, 1, capabilityVersion,
                List.copyOf(itemViews), now.toInstant(ZoneOffset.UTC), false);
        logRequest(request.requestId(), fingerprint, "JOINT_CREATE", response);
        return response;
    }

    // ---------- 分样 ----------

    /**
     * 分样：覆盖联检单全部项目且每项目恰好一次，样本号全局唯一，
     * 机构必须具备对应项目能力；任一项失败整单不分样（同事务回滚）。
     */
    @Transactional
    public JointAssignResponse assign(JointAssignRequest request) {
        String fingerprint = Fingerprint.structured(joinParts("JOINT_ASSIGN",
                request.requestId(), String.valueOf(request.orderId()),
                String.valueOf(request.expectedVersion()), assignmentParts(request.assignments())));
        JointDao.JointOrderRow order = jointDao.lockOrderById(request.orderId())
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + request.orderId()));
        // 行锁后做幂等检查：并发同键请求串行化，后到者重放首次冻结结果
        JointAssignResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                JointAssignResponse.class);
        if (replayed != null) {
            return replayed;
        }
        requireVersion(order, request.expectedVersion());
        if (!STATUS_CREATED.equals(order.status())) {
            throw ApiException.unprocessable("联检单状态不允许分样: " + order.status());
        }
        List<JointDao.JointItemRow> orderItems = jointDao.findItems(order.id());
        Set<String> required = orderItems.stream().map(JointDao.JointItemRow::item)
                .collect(Collectors.toSet());
        Set<String> provided = new HashSet<>();
        Set<String> sampleNos = new HashSet<>();
        for (AssignmentItem assignment : request.assignments()) {
            if (!provided.add(assignment.item())) {
                throw ApiException.unprocessable("项目重复分样: " + assignment.item());
            }
            if (!sampleNos.add(assignment.sampleNo())) {
                throw ApiException.unprocessable("样本号重复: " + assignment.sampleNo());
            }
        }
        if (!provided.equals(required)) {
            throw ApiException.unprocessable("分样须覆盖全部项目且每项目恰好一次");
        }
        LocalDateTime now = nowUtc();
        List<AssignmentView> views = new ArrayList<>();
        for (AssignmentItem assignment : request.assignments()) {
            JointDao.AgencyRow agency = jointDao.findAgency(assignment.agencyCode())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "机构不存在: " + assignment.agencyCode()));
            if (!jointDao.agencyCapable(assignment.agencyCode(), assignment.item())) {
                throw ApiException.unprocessable("机构不具备项目能力: "
                        + assignment.agencyCode() + "/" + assignment.item());
            }
            try {
                jointDao.insertAssignment(order.id(), assignment.item(), assignment.sampleNo(),
                        agency.code(), agency.capabilityVersion(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.unprocessable("样本号已被占用（全局唯一）: " + assignment.sampleNo());
            }
            views.add(new AssignmentView(assignment.item(), assignment.sampleNo(),
                    agency.code(), agency.capabilityVersion()));
        }
        advanceOrConflict(order.id(), request.expectedVersion(), STATUS_ASSIGNED, null, now);
        JointAssignResponse response = new JointAssignResponse(order.id(), request.requestId(),
                STATUS_ASSIGNED, order.version() + 1, List.copyOf(views),
                now.toInstant(ZoneOffset.UTC), false);
        logRequest(request.requestId(), fingerprint, "JOINT_ASSIGN", response);
        return response;
    }

    // ---------- 结果与复测 ----------

    /**
     * 录入结果/复测：结果引用样本、项目与联检版本；浓度有限非负，恰等限值合格。
     * 复测仅在综合判定前替换该项当前结果，旧结果保留、结果版本递增；
     * 已综合判定后只记补充信息（迟到事件），不重开判定。
     */
    @Transactional
    public JointResultResponse submitResult(JointResultRequest request) {
        String fingerprint = Fingerprint.structured(joinParts("JOINT_RESULT",
                request.requestId(), String.valueOf(request.orderId()),
                String.valueOf(request.expectedVersion()), request.item(), request.sampleNo(),
                request.concentration().toPlainString(), request.occurredAt().toString(),
                String.valueOf(request.remeasure())));
        JointDao.JointOrderRow order = jointDao.lockOrderById(request.orderId())
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + request.orderId()));
        // 行锁后做幂等检查：并发同键请求串行化，后到者重放首次冻结结果
        JointResultResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                JointResultResponse.class);
        if (replayed != null) {
            return replayed;
        }
        LocalDateTime now = nowUtc();
        if (STATUS_DETERMINED.equals(order.status())) {
            // 已综合判定：只记补充信息，不重开判定、不变更版本
            ledgerDao.insertLateEvent(order.batchId(), "LATE_JOINT_RESULT",
                    "orderId=" + order.id() + ", item=" + request.item()
                            + ", sampleNo=" + request.sampleNo()
                            + ", concentration=" + request.concentration()
                            + ", remeasure=" + request.remeasure(), now);
            JointResultResponse supplementary = new JointResultResponse(order.id(),
                    request.requestId(), request.item(), request.sampleNo(), order.version(),
                    null, request.concentration(), null, order.status(),
                    request.occurredAt(), now.toInstant(ZoneOffset.UTC), false, true);
            logRequest(request.requestId(), fingerprint, "JOINT_RESULT", supplementary);
            return supplementary;
        }
        requireVersion(order, request.expectedVersion());
        if (!STATUS_ASSIGNED.equals(order.status())) {
            throw ApiException.unprocessable("联检单未分样，不得录入结果: " + order.status());
        }
        JointDao.JointItemRow orderItem = jointDao.findItems(order.id()).stream()
                .filter(it -> it.item().equals(request.item()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("项目不属于联检单: " + request.item()));
        JointDao.AssignmentRow assignment = jointDao.findAssignment(order.id(), request.item())
                .orElseThrow(() -> ApiException.unprocessable("项目未分样: " + request.item()));
        if (!assignment.sampleNo().equals(request.sampleNo())) {
            throw ApiException.unprocessable("样本号与分样不一致: " + request.sampleNo());
        }
        int currentVersion = jointDao.maxResultVersion(order.id(), request.item());
        if (!request.remeasure() && currentVersion > 0) {
            throw ApiException.unprocessable("项目已有结果，须以复测替换: " + request.item());
        }
        if (request.remeasure() && currentVersion == 0) {
            throw ApiException.unprocessable("项目尚无当前结果，复测须先录入首次结果: " + request.item());
        }
        boolean qualified = request.concentration().compareTo(orderItem.limitValue()) <= 0;
        int resultVersion = currentVersion + 1;
        jointDao.insertResult(order.id(), request.item(), request.sampleNo(), order.version(),
                resultVersion, request.concentration(), qualified,
                LocalDateTime.ofInstant(request.occurredAt(), ZoneOffset.UTC), now);
        advanceOrConflict(order.id(), request.expectedVersion(), STATUS_ASSIGNED, null, now);
        JointResultResponse response = new JointResultResponse(order.id(), request.requestId(),
                request.item(), request.sampleNo(), order.version(), resultVersion,
                request.concentration(), qualified, STATUS_ASSIGNED, request.occurredAt(),
                now.toInstant(ZoneOffset.UTC), false, false);
        logRequest(request.requestId(), fingerprint, "JOINT_RESULT", response);
        return response;
    }

    // ---------- 综合判定 ----------

    /**
     * 综合判定：全部项目均有当前结果方可判定（尚缺一项只能待定）；
     * 任一项目超限整体不合格。判定携带全部项结果版本并冻结证据；
     * 合格同事务放行准入，不合格同事务转处置并留样 48 小时；
     * 并发复测导致版本失配则判定零写入。
     */
    @Transactional
    public JointDetermineResponse determine(JointDetermineRequest request) {
        String fingerprint = Fingerprint.structured(joinParts("JOINT_DETERMINE",
                request.requestId(), String.valueOf(request.orderId()),
                String.valueOf(request.expectedVersion())));
        JointDao.JointOrderRow order = jointDao.lockOrderById(request.orderId())
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + request.orderId()));
        // 行锁后做幂等检查：并发同键请求串行化，后到者重放首次冻结结果
        JointDetermineResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                JointDetermineResponse.class);
        if (replayed != null) {
            return replayed;
        }
        requireVersion(order, request.expectedVersion());
        if (!STATUS_ASSIGNED.equals(order.status())) {
            throw ApiException.unprocessable("联检单状态不允许综合判定: " + order.status());
        }
        List<JointDao.JointItemRow> orderItems = jointDao.findItems(order.id());
        Map<String, JointDao.JointResultRow> current = jointDao.findCurrentResults(order.id())
                .stream().collect(Collectors.toMap(JointDao.JointResultRow::item, Function.identity()));
        List<String> missing = orderItems.stream().map(JointDao.JointItemRow::item)
                .filter(item -> !current.containsKey(item)).toList();
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("尚缺项目结果，只能待定: " + String.join(",", missing));
        }
        boolean pass = orderItems.stream()
                .allMatch(item -> current.get(item.item()).qualified());
        String overall = pass ? RESULT_PASS : RESULT_FAIL;
        LocalDateTime now = nowUtc();
        // 版本失配（并发复测）则更新 0 行并整体回滚：判定零写入
        advanceOrConflict(order.id(), request.expectedVersion(), STATUS_DETERMINED, overall, now);
        long determinationId = jointDao.insertDetermination(order.id(), overall, now);
        List<DeterminationItemView> evidence = new ArrayList<>();
        for (JointDao.JointItemRow item : orderItems) {
            JointDao.JointResultRow result = current.get(item.item());
            jointDao.insertDeterminationItem(determinationId, item.item(),
                    result.resultVersion(), result.concentration(), result.qualified());
            evidence.add(new DeterminationItemView(item.item(), result.resultVersion(),
                    result.concentration(), result.qualified()));
        }
        BatchDao.BatchRow batch = batchDao.lockById(order.batchId()).orElseThrow();
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("批次状态不允许联检判定落地: " + batch.status());
        }
        String batchStatus;
        Instant retentionUntil = null;
        if (pass) {
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
        JointDetermineResponse response = new JointDetermineResponse(order.id(),
                request.requestId(), overall, order.version() + 1, List.copyOf(evidence),
                batchStatus, retentionUntil, now.toInstant(ZoneOffset.UTC), false);
        logRequest(request.requestId(), fingerprint, "JOINT_DETERMINE", response);
        return response;
    }

    // ---------- 查询 ----------

    /**
     * 联检单详情：冻结项目、分样、各项目当前结果与判定证据。
     */
    @Transactional(readOnly = true)
    public JointOrderResponse detail(long orderId) {
        JointDao.JointOrderRow order = jointDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        List<JointItemView> items = jointDao.findItems(orderId).stream()
                .map(it -> new JointItemView(it.item(), it.limitValue(), it.catalogVersion()))
                .toList();
        List<AssignmentView> assignments = jointDao.findAssignments(orderId).stream()
                .map(a -> new AssignmentView(a.item(), a.sampleNo(), a.agencyCode(),
                        a.agencyCapabilityVersion()))
                .toList();
        List<JointResultView> currentResults = jointDao.findCurrentResults(orderId).stream()
                .map(r -> new JointResultView(r.item(), r.sampleNo(), r.orderVersion(),
                        r.resultVersion(), r.concentration(), r.qualified(),
                        r.occurredAt().toInstant(ZoneOffset.UTC)))
                .toList();
        List<DeterminationItemView> determination = jointDao.findDetermination(orderId)
                .map(det -> jointDao.findDeterminationItems(det.id()).stream()
                        .map(di -> new DeterminationItemView(di.item(), di.resultVersion(),
                                di.concentration(), di.qualified()))
                        .toList())
                .orElse(List.of());
        return new JointOrderResponse(order.id(), order.batchId(), order.sourceTestId(),
                order.status(), order.version(), order.capabilityVersion(), order.overallResult(),
                order.createdAt().toInstant(ZoneOffset.UTC),
                order.updatedAt().toInstant(ZoneOffset.UTC),
                items, assignments, currentResults, determination);
    }

    /**
     * 合成项目目录与机构能力查询。
     */
    @Transactional(readOnly = true)
    public CatalogResponse catalog() {
        List<CatalogItemView> items = jointDao.findCatalogItems().stream()
                .map(c -> new CatalogItemView(c.item(), c.limitValue(), c.catalogVersion()))
                .toList();
        List<AgencyView> agencies = jointDao.findAgencies().stream()
                .map(a -> new AgencyView(a.code(), a.name(), a.capabilityVersion(),
                        jointDao.findAgencyItems(a.code())))
                .toList();
        return new CatalogResponse(jointDao.capabilityDirectoryVersion(), items, agencies);
    }

    // ---------- 内部辅助 ----------

    /**
     * 幂等重放：requestId 已登记时，同参返回首次冻结结果（replayed=true），异参 409。
     */
    private <T> T replayIfPresent(String requestId, String fingerprint, Class<T> type) {
        var existing = jointDao.findRequestLog(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        JointDao.RequestLogRow log = existing.get();
        if (!log.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已使用且参数不一致");
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(log.response());
            node.put("replayed", true);
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照解析失败", e);
        }
    }

    /**
     * 登记幂等台账（与业务同事务，失败回滚不占键）；
     * 并发同键冲突时重放首次结果或 409。
     */
    private void logRequest(String requestId, String fingerprint, String operation,
                            Object response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
        try {
            jointDao.insertRequestLog(requestId, fingerprint, operation, json, nowUtc());
        } catch (DuplicateKeyException e) {
            JointDao.RequestLogRow raced = jointDao.findRequestLog(requestId).orElseThrow();
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且参数不一致");
            }
            throw ApiException.conflict("requestId 并发冲突，请重试");
        }
    }

    /**
     * 校验期望版本与当前版本一致，不一致 409（零写入由调用方事务回滚保证）。
     */
    private void requireVersion(JointDao.JointOrderRow order, long expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("版本失配: expectedVersion=" + expectedVersion
                    + ", 当前版本=" + order.version());
        }
    }

    /**
     * 乐观推进联检单版本；并发变更导致 0 行更新时 409，同事务回滚保证零写入。
     */
    private void advanceOrConflict(long orderId, long expectedVersion, String newStatus,
                                   String overallResult, LocalDateTime now) {
        int updated = jointDao.advanceOrder(orderId, (int) expectedVersion, newStatus,
                overallResult, now);
        if (updated == 0) {
            throw ApiException.conflict("版本失配，并发变更导致零写入: expectedVersion="
                    + expectedVersion);
        }
    }

    private List<String> joinParts(String... parts) {
        return List.of(parts);
    }

    private List<String> joinParts(String head, String p1, String p2, String p3,
                                   List<String> tail) {
        List<String> parts = new ArrayList<>();
        parts.add(head);
        parts.add(p1);
        parts.add(p2);
        parts.add(p3);
        parts.addAll(tail);
        return parts;
    }

    private List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private List<String> assignmentParts(List<AssignmentItem> assignments) {
        return assignments.stream()
                .sorted(Comparator.comparing(AssignmentItem::item))
                .flatMap(a -> java.util.stream.Stream.of(a.item(), a.sampleNo(), a.agencyCode()))
                .toList();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
