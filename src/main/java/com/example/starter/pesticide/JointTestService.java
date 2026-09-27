package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestItemVersionView;
import com.example.starter.pesticide.dto.ApiDtos.JointTestItemView;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultView;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 疑似批次定量联检服务：联检单版本门控（版本从 1 开始、成功变更加 1）、
 * 分样覆盖性门控、结果版本化复测、综合判定冻结证据并与准入/处置同事务。
 * 全部写操作 requestId 幂等：同键同参重放首次冻结结果，异参 409，失败不占键。
 */
@Service
public class JointTestService {

    /** 联检单状态：已创建 */
    public static final String STATUS_CREATED = "CREATED";
    /** 联检单状态：已分样 */
    public static final String STATUS_SPLIT = "SPLIT";
    /** 联检单状态：已综合判定（终态） */
    public static final String STATUS_JUDGED = "JUDGED";
    /** 综合结论：合格 */
    public static final String OVERALL_QUALIFIED = "QUALIFIED";
    /** 综合结论：不合格 */
    public static final String OVERALL_UNQUALIFIED = "UNQUALIFIED";
    /** 综合结论：待定（尚缺项目结果，不落库、不占键、不推进版本） */
    public static final String OVERALL_PENDING = "PENDING";
    /** 综合不合格转处置留样时长（单位：小时），沿用原复检规则 */
    public static final int RETENTION_HOURS = 48;

    private final JointTestDao jointTestDao;
    private final BatchDao batchDao;
    private final LedgerDao ledgerDao;
    private final Clock clock;

    public JointTestService(JointTestDao jointTestDao, BatchDao batchDao, LedgerDao ledgerDao,
                            Clock clock) {
        this.jointTestDao = jointTestDao;
        this.batchDao = batchDao;
        this.ledgerDao = ledgerDao;
        this.clock = clock;
    }

    /**
     * 联检创建：疑似冻结批次建立联检单，逐项限值与机构能力版本从合成目录冻结；
     * 项目至少两种且唯一；requestId 幂等。创建后版本为 1。
     */
    @Transactional
    public JointTestCreateResponse create(JointTestCreateRequest request) {
        Set<String> distinct = new HashSet<>(request.items());
        if (distinct.size() != request.items().size()) {
            throw ApiException.badRequest("联检项目存在重复");
        }
        String fingerprint = Fingerprint.structured(request.requestId(),
                String.valueOf(request.batchId()), request.agencyCode(),
                canonicalStrings(request.items()), request.occurredAt().toString());
        var existing = jointTestDao.findOrderByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointTestDao.OrderRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toCreateResponse(row, true);
        }
        BatchDao.BatchRow batch = batchDao.lockById(request.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + request.batchId()));
        // 行锁后复查：并发同键创建可能已先行提交
        var afterLock = jointTestDao.findOrderByRequestId(request.requestId());
        if (afterLock.isPresent()) {
            if (!afterLock.get().fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toCreateResponse(afterLock.get(), true);
        }
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("批次非疑似冻结状态，不得建立联检单: " + batch.status());
        }
        if (jointTestDao.findOrderByBatchId(batch.id()).isPresent()) {
            throw ApiException.unprocessable("批次已建立联检单，不得重复创建");
        }
        // 逐项冻结限值与机构能力版本（引用合成目录）
        Map<String, JointTestDao.CatalogRow> catalog = new LinkedHashMap<>();
        Map<String, Integer> capabilities = new LinkedHashMap<>();
        for (String item : request.items()) {
            catalog.put(item, jointTestDao.findCatalogItem(item)
                    .orElseThrow(() -> ApiException.unprocessable("合成项目目录无该项目: " + item)));
            capabilities.put(item, jointTestDao.findCapabilityVersion(request.agencyCode(), item)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "机构不具备项目能力: " + request.agencyCode() + "/" + item)));
        }
        LocalDateTime now = nowUtc();
        long orderId;
        try {
            orderId = jointTestDao.insertOrder(request.requestId(), fingerprint, batch.id(),
                    request.agencyCode(), now);
            for (String item : request.items()) {
                jointTestDao.insertItem(orderId, item, catalog.get(item).limitValue(),
                        capabilities.get(item));
            }
        } catch (DuplicateKeyException e) {
            var raced = jointTestDao.findOrderByRequestId(request.requestId());
            if (raced.isPresent()) {
                if (raced.get().fingerprint().equals(fingerprint)) {
                    return toCreateResponse(raced.get(), true);
                }
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            throw ApiException.unprocessable("批次已建立联检单，不得重复创建");
        }
        JointTestDao.OrderRow row = jointTestDao.findOrderById(orderId).orElseThrow();
        return toCreateResponse(row, false);
    }

    /**
     * 分样分配：须覆盖全部项目、样本号全局唯一、机构具备对应项目能力；
     * 任一项失败整单不分样（事务回滚）；expectedVersion 门控。
     */
    @Transactional
    public JointTestSplitResponse split(long orderId, JointTestSplitRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), canonicalSplits(request.splits()),
                request.occurredAt().toString());
        List<JointTestDao.SplitRow> existing = jointTestDao.findSplitsByRequestId(request.requestId());
        if (!existing.isEmpty()) {
            if (!existing.get(0).fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toSplitResponse(orderId, request.requestId(), existing, true);
        }
        JointTestDao.OrderRow order = lockOrder(orderId);
        // 行锁后复查：并发同键分样可能已先行提交
        List<JointTestDao.SplitRow> afterLock = jointTestDao.findSplitsByRequestId(request.requestId());
        if (!afterLock.isEmpty()) {
            if (!afterLock.get(0).fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toSplitResponse(orderId, request.requestId(), afterLock, true);
        }
        if (STATUS_JUDGED.equals(order.status())) {
            throw ApiException.unprocessable("联检单已综合判定，不得分样");
        }
        requireVersion(order, request.expectedVersion());
        if (STATUS_SPLIT.equals(order.status())) {
            throw ApiException.unprocessable("联检单已分样，不得重复分样");
        }
        Set<String> splitItems = new HashSet<>();
        Set<String> sampleNos = new HashSet<>();
        for (JointTestSplitItem split : request.splits()) {
            if (!splitItems.add(split.item())) {
                throw ApiException.badRequest("分样项目存在重复: " + split.item());
            }
            if (!sampleNos.add(split.sampleNo())) {
                throw ApiException.badRequest("分样样本号存在重复: " + split.sampleNo());
            }
        }
        List<JointTestDao.ItemRow> items = jointTestDao.findItems(orderId);
        Set<String> required = items.stream().map(JointTestDao.ItemRow::item).collect(Collectors.toSet());
        if (!splitItems.equals(required)) {
            throw ApiException.unprocessable("分样须覆盖全部联检项目，任一项失败整单不分样");
        }
        for (JointTestSplitItem split : request.splits()) {
            jointTestDao.findCapabilityVersion(order.agencyCode(), split.item())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "机构不具备项目能力，整单不分样: " + order.agencyCode() + "/" + split.item()));
            if (jointTestDao.sampleNoExists(split.sampleNo())) {
                throw ApiException.unprocessable("样本号已占用，整单不分样: " + split.sampleNo());
            }
        }
        LocalDateTime now = nowUtc();
        int newVersion = order.version() + 1;
        try {
            for (JointTestSplitItem split : request.splits()) {
                jointTestDao.insertSplit(orderId, request.requestId(), fingerprint, split.item(),
                        split.sampleNo(), newVersion, now);
            }
        } catch (DuplicateKeyException e) {
            var raced = jointTestDao.findSplitsByRequestId(request.requestId());
            // 仅当已存在的分样行数等于本次完整集合且指纹一致时才可判定为并发重放；
            // 否则为本事务部分写入或样本号冲突，整体回滚（整单不分样）
            if (raced.size() == request.splits().size()
                    && raced.get(0).fingerprint().equals(fingerprint)) {
                return toSplitResponse(orderId, request.requestId(), raced, true);
            }
            if (!raced.isEmpty() && raced.size() == request.splits().size()) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            throw ApiException.unprocessable("样本号已被占用或分样冲突，整单不分样");
        }
        requireBump(orderId, order.version(), STATUS_SPLIT, null, now);
        return toSplitResponse(orderId, request.requestId(),
                jointTestDao.findSplitsByRequestId(request.requestId()), false);
    }

    /**
     * 联检结果/复测：结果引用样本、项目与联检版本；浓度有限非负，恰等限值合格；
     * 综合判定前复测替换该项当前结果（保留旧结果、项目版本递增）；
     * 综合判定后只记补充信息，不重开、不推进版本。
     */
    @Transactional
    public JointTestResultResponse submitResult(long orderId, JointTestResultRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), request.item(),
                canonicalDecimal(request.concentration()), request.occurredAt().toString());
        var existing = jointTestDao.findResultByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointTestDao.ResultRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toResultResponse(row, true);
        }
        JointTestDao.OrderRow order = lockOrder(orderId);
        // 行锁后复查：并发同键结果可能已先行提交
        var afterLock = jointTestDao.findResultByRequestId(request.requestId());
        if (afterLock.isPresent()) {
            if (!afterLock.get().fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toResultResponse(afterLock.get(), true);
        }
        requireVersion(order, request.expectedVersion());
        JointTestDao.ItemRow item = jointTestDao.findItems(orderId).stream()
                .filter(i -> i.item().equals(request.item()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("项目不属于本联检单: " + request.item()));
        JointTestDao.SplitRow split = jointTestDao.findSplit(orderId, request.item())
                .orElseThrow(() -> ApiException.unprocessable("项目未分样，不得录入结果: " + request.item()));
        boolean qualified = request.concentration().compareTo(item.limitValue()) <= 0;
        LocalDateTime now = nowUtc();
        LocalDateTime occurredAt = LocalDateTime.ofInstant(request.occurredAt(), ZoneOffset.UTC);
        if (STATUS_JUDGED.equals(order.status())) {
            // 已综合判定：只记补充信息，不重开、不推进版本
            int latestVersion = jointTestDao.findCurrentResult(orderId, request.item())
                    .map(JointTestDao.ResultRow::itemVersion).orElse(0);
            try {
                jointTestDao.insertResult(request.requestId(), fingerprint, orderId,
                        split.id(), request.item(), request.concentration(), qualified,
                        latestVersion, order.version(), false, true, occurredAt, now);
            } catch (DuplicateKeyException e) {
                var raced = jointTestDao.findResultByRequestId(request.requestId());
                if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                    return toResultResponse(raced.get(), true);
                }
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toResultResponse(jointTestDao.findResultByRequestId(request.requestId())
                    .orElseThrow(), false);
        }
        var current = jointTestDao.findCurrentResult(orderId, request.item());
        int itemVersion = 1;
        if (current.isPresent()) {
            // 复测：替换该项当前结果，保留旧结果并递增项目版本
            jointTestDao.markResultNotCurrent(current.get().id());
            itemVersion = current.get().itemVersion() + 1;
        }
        int newVersion = order.version() + 1;
        try {
            jointTestDao.insertResult(request.requestId(), fingerprint, orderId, split.id(),
                    request.item(), request.concentration(), qualified, itemVersion, newVersion,
                    true, false, occurredAt, now);
        } catch (DuplicateKeyException e) {
            var raced = jointTestDao.findResultByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toResultResponse(raced.get(), true);
            }
            throw ApiException.conflict("requestId 已使用且业务字段不一致");
        }
        requireBump(orderId, order.version(), order.status(), null, now);
        return toResultResponse(jointTestDao.findResultByRequestId(request.requestId()).orElseThrow(),
                false);
    }

    /**
     * 综合判定：携带全部项目结果版本，版本失配零写入；任一超限整体不合格，恰等限值合格；
     * 尚缺一项只能待定（不落库、不占键、不推进版本）；合格同事务放行，不合格转处置并留样 48 小时。
     */
    @Transactional
    public JointTestJudgeResponse judge(long orderId, JointTestJudgeRequest request) {
        String fingerprint = Fingerprint.structured(request.requestId(), String.valueOf(orderId),
                String.valueOf(request.expectedVersion()), canonicalJudgeItems(request.items()),
                request.occurredAt().toString());
        var existing = jointTestDao.findJudgmentByRequestId(request.requestId());
        if (existing.isPresent()) {
            JointTestDao.JudgmentRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toJudgeResponse(orderId, row, true);
        }
        JointTestDao.OrderRow order = lockOrder(orderId);
        // 行锁后复查：并发同键判定可能已先行提交
        var afterLock = jointTestDao.findJudgmentByRequestId(request.requestId());
        if (afterLock.isPresent()) {
            if (!afterLock.get().fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已使用且业务字段不一致");
            }
            return toJudgeResponse(orderId, afterLock.get(), true);
        }
        if (STATUS_JUDGED.equals(order.status())) {
            throw ApiException.unprocessable("联检单已综合判定，不得重复判定");
        }
        requireVersion(order, request.expectedVersion());
        Map<String, Integer> requestedVersions = new HashMap<>();
        for (JointTestJudgeItem item : request.items()) {
            if (requestedVersions.put(item.item(), item.resultVersion()) != null) {
                throw ApiException.badRequest("项目版本集合存在重复: " + item.item());
            }
        }
        List<JointTestDao.ItemRow> items = jointTestDao.findItems(orderId);
        Map<String, JointTestDao.ResultRow> currentByItem = new HashMap<>();
        for (JointTestDao.ResultRow row : jointTestDao.findCurrentResults(orderId)) {
            currentByItem.put(row.item(), row);
        }
        BatchDao.BatchRow batch = batchDao.lockById(order.batchId())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + order.batchId()));
        boolean anyMissing = items.stream().anyMatch(i -> !currentByItem.containsKey(i.item()));
        if (anyMissing) {
            // 尚缺项目结果：只能待定，不落库、不占键、不推进版本
            return new JointTestJudgeResponse(null, request.requestId(), orderId, OVERALL_PENDING,
                    order.version(), batch.id(), batch.status(), null,
                    itemVersionViews(items, currentByItem), null, false);
        }
        // 判定前校验全部项目结果版本，并发复测致版本失配则零写入
        for (JointTestDao.ItemRow item : items) {
            Integer requested = requestedVersions.get(item.item());
            int actual = currentByItem.get(item.item()).itemVersion();
            if (requested == null || requested != actual) {
                throw ApiException.conflict("项目结果版本失配，判定零写入: " + item.item());
            }
        }
        boolean allQualified = items.stream()
                .allMatch(i -> currentByItem.get(i.item()).qualified());
        String overall = allQualified ? OVERALL_QUALIFIED : OVERALL_UNQUALIFIED;
        if (BatchStatus.valueOf(batch.status()) != BatchStatus.FROZEN) {
            throw ApiException.unprocessable("批次非疑似冻结状态，不得综合判定: " + batch.status());
        }
        LocalDateTime now = nowUtc();
        int newVersion = order.version() + 1;
        String evidence = buildEvidence(items, currentByItem);
        long judgmentId;
        try {
            judgmentId = jointTestDao.insertJudgment(request.requestId(), fingerprint, orderId,
                    overall, evidence, newVersion, now);
        } catch (DuplicateKeyException e) {
            var raced = jointTestDao.findJudgmentByRequestId(request.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toJudgeResponse(orderId, raced.get(), true);
            }
            throw ApiException.conflict("联检单已综合判定，或 requestId 冲突");
        }
        requireBump(orderId, order.version(), STATUS_JUDGED, overall, now);
        String batchStatus;
        Instant retentionUntil = null;
        if (OVERALL_QUALIFIED.equals(overall)) {
            // 综合合格：同事务更新原批准入状态
            ledgerDao.insertAdmission(batch.id(), now);
            batchDao.updateStatus(batch.id(), BatchStatus.RELEASED.name(), now);
            batchStatus = BatchStatus.RELEASED.name();
        } else {
            // 综合不合格：转原处置流程并按原规则留样 48 小时
            ledgerDao.insertDisposal(batch.id(), "定量联检综合判定不合格，转监督处置", now);
            LocalDateTime until = now.plusHours(RETENTION_HOURS);
            ledgerDao.insertRetention(batch.id(), RETENTION_HOURS, until, now);
            batchDao.updateStatus(batch.id(), BatchStatus.DISPOSED.name(), now);
            batchStatus = BatchStatus.DISPOSED.name();
            retentionUntil = until.toInstant(ZoneOffset.UTC);
        }
        return new JointTestJudgeResponse(judgmentId, request.requestId(), orderId, overall,
                newVersion, batch.id(), batchStatus, retentionUntil,
                itemVersionViews(items, currentByItem), now.toInstant(ZoneOffset.UTC), false);
    }

    /**
     * 联检单详情：冻结项目、分样、全部结果（含历史与补充）与判定证据。
     */
    @Transactional(readOnly = true)
    public JointTestDetailResponse detail(long orderId) {
        JointTestDao.OrderRow order = jointTestDao.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
        List<JointTestItemView> items = jointTestDao.findItems(orderId).stream()
                .map(i -> new JointTestItemView(i.item(), i.limitValue(), i.capabilityVersion()))
                .toList();
        List<JointTestSplitView> splits = jointTestDao.findSplits(orderId).stream()
                .map(s -> new JointTestSplitView(s.item(), s.sampleNo()))
                .toList();
        Map<Long, String> sampleNoBySplitId = jointTestDao.findSplits(orderId).stream()
                .collect(Collectors.toMap(JointTestDao.SplitRow::id, JointTestDao.SplitRow::sampleNo));
        List<JointTestResultView> results = jointTestDao.findResults(orderId).stream()
                .map(r -> new JointTestResultView(r.id(), r.item(),
                        sampleNoBySplitId.get(r.splitId()), r.concentration(), r.qualified(),
                        r.itemVersion(), r.current(), r.supplementary(),
                        r.occurredAt().toInstant(ZoneOffset.UTC),
                        r.createdAt().toInstant(ZoneOffset.UTC)))
                .toList();
        List<JointTestItemVersionView> judgmentVersions = jointTestDao.findJudgmentByOrderId(orderId)
                .map(j -> itemVersionViews(jointTestDao.findItems(orderId),
                        jointTestDao.findCurrentResults(orderId).stream()
                                .collect(Collectors.toMap(JointTestDao.ResultRow::item, r -> r))))
                .orElse(List.of());
        return new JointTestDetailResponse(order.id(), order.batchId(), order.agencyCode(),
                order.version(), order.status(), order.overallResult(), items, splits, results,
                judgmentVersions, order.createdAt().toInstant(ZoneOffset.UTC),
                order.updatedAt().toInstant(ZoneOffset.UTC));
    }

    private JointTestDao.OrderRow lockOrder(long orderId) {
        return jointTestDao.lockOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("联检单不存在: " + orderId));
    }

    private void requireVersion(JointTestDao.OrderRow order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("联检单版本失配：期望 " + expectedVersion
                    + "，当前 " + order.version());
        }
    }

    private void requireBump(long orderId, int expectedVersion, String newStatus, String overall,
                             LocalDateTime now) {
        if (jointTestDao.bumpVersion(orderId, expectedVersion, newStatus, overall, now) == 0) {
            throw ApiException.conflict("联检单版本失配，变更零写入");
        }
    }

    private JointTestCreateResponse toCreateResponse(JointTestDao.OrderRow row, boolean replayed) {
        List<JointTestItemView> items = jointTestDao.findItems(row.id()).stream()
                .map(i -> new JointTestItemView(i.item(), i.limitValue(), i.capabilityVersion()))
                .toList();
        return new JointTestCreateResponse(row.id(), row.requestId(), row.batchId(), row.agencyCode(),
                row.version(), row.status(), items, row.createdAt().toInstant(ZoneOffset.UTC),
                replayed);
    }

    private JointTestSplitResponse toSplitResponse(long orderId, String requestId,
                                                   List<JointTestDao.SplitRow> splits,
                                                   boolean replayed) {
        List<JointTestSplitView> views = splits.stream()
                .map(s -> new JointTestSplitView(s.item(), s.sampleNo()))
                .toList();
        Instant createdAt = splits.isEmpty() ? null
                : splits.get(0).createdAt().toInstant(ZoneOffset.UTC);
        Integer version = splits.isEmpty() ? null : splits.get(0).orderVersion();
        return new JointTestSplitResponse(orderId, requestId, version, STATUS_SPLIT, views,
                createdAt, replayed);
    }

    private JointTestResultResponse toResultResponse(JointTestDao.ResultRow row, boolean replayed) {
        BigDecimal limit = jointTestDao.findItems(row.orderId()).stream()
                .filter(i -> i.item().equals(row.item()))
                .map(JointTestDao.ItemRow::limitValue)
                .findFirst()
                .orElse(null);
        return new JointTestResultResponse(row.id(), row.requestId(), row.orderId(), row.item(),
                row.concentration(), limit, row.qualified(), row.itemVersion(), row.orderVersion(),
                row.supplementary(), row.createdAt().toInstant(ZoneOffset.UTC), replayed);
    }

    private JointTestJudgeResponse toJudgeResponse(long orderId, JointTestDao.JudgmentRow row,
                                                   boolean replayed) {
        JointTestDao.OrderRow order = jointTestDao.findOrderById(orderId).orElseThrow();
        BatchDao.BatchRow batch = batchDao.findById(order.batchId()).orElseThrow();
        Instant retentionUntil = ledgerDao.findRetention(order.batchId())
                .map(r -> r.retainUntil().toInstant(ZoneOffset.UTC))
                .orElse(null);
        List<JointTestItemVersionView> views = itemVersionViews(jointTestDao.findItems(orderId),
                jointTestDao.findCurrentResults(orderId).stream()
                        .collect(Collectors.toMap(JointTestDao.ResultRow::item, r -> r)));
        return new JointTestJudgeResponse(row.id(), row.requestId(), orderId, row.overallResult(),
                row.orderVersion(), batch.id(), batch.status(), retentionUntil, views,
                row.createdAt().toInstant(ZoneOffset.UTC), replayed);
    }

    private List<JointTestItemVersionView> itemVersionViews(
            List<JointTestDao.ItemRow> items, Map<String, JointTestDao.ResultRow> currentByItem) {
        List<JointTestItemVersionView> views = new ArrayList<>();
        for (JointTestDao.ItemRow item : items) {
            JointTestDao.ResultRow result = currentByItem.get(item.item());
            if (result == null) {
                views.add(new JointTestItemVersionView(item.item(), null, null,
                        item.limitValue(), null));
            } else {
                views.add(new JointTestItemVersionView(item.item(), result.itemVersion(),
                        result.concentration(), item.limitValue(), result.qualified()));
            }
        }
        return views;
    }

    private String buildEvidence(List<JointTestDao.ItemRow> items,
                                 Map<String, JointTestDao.ResultRow> currentByItem) {
        StringBuilder evidence = new StringBuilder();
        items.stream().map(JointTestDao.ItemRow::item).sorted().forEach(item -> {
            JointTestDao.ResultRow result = currentByItem.get(item);
            BigDecimal limit = items.stream()
                    .filter(i -> i.item().equals(item)).findFirst().orElseThrow().limitValue();
            evidence.append(Fingerprint.encode(item))
                    .append(Fingerprint.encode(String.valueOf(result.itemVersion())))
                    .append(Fingerprint.encode(canonicalDecimal(result.concentration())))
                    .append(Fingerprint.encode(canonicalDecimal(limit)))
                    .append(Fingerprint.encode(String.valueOf(result.qualified())));
        });
        return evidence.toString();
    }

    private static String canonicalStrings(List<String> values) {
        return values.stream().sorted().map(Fingerprint::encode).collect(Collectors.joining());
    }

    private static String canonicalSplits(List<JointTestSplitItem> splits) {
        return splits.stream()
                .map(s -> Fingerprint.encode(s.item()) + Fingerprint.encode(s.sampleNo()))
                .sorted()
                .collect(Collectors.joining());
    }

    private static String canonicalJudgeItems(List<JointTestJudgeItem> items) {
        return items.stream()
                .map(i -> Fingerprint.encode(i.item())
                        + Fingerprint.encode(String.valueOf(i.resultVersion())))
                .sorted()
                .collect(Collectors.joining());
    }

    private static String canonicalDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
