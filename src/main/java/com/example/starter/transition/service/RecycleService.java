package com.example.starter.transition.service;

import com.example.starter.transition.ApiException;
import com.example.starter.transition.TransitionStatus;
import com.example.starter.transition.dto.FeedRequest;
import com.example.starter.transition.dto.FeedView;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.RecycleQuotaView;
import com.example.starter.transition.dto.ReduceRecycleQuotaRequest;
import com.example.starter.transition.dto.RegisterRecycleQuotaRequest;
import com.example.starter.transition.model.BlendRatioVersion;
import com.example.starter.transition.model.FeedApplication;
import com.example.starter.transition.model.FeedConsumption;
import com.example.starter.transition.model.IdempotentRequest;
import com.example.starter.transition.model.RecycleQuota;
import com.example.starter.transition.model.TransitionOrder;
import com.example.starter.transition.model.TransitionRoll;
import com.example.starter.transition.repo.BlendRatioVersionRepository;
import com.example.starter.transition.repo.FeedApplicationRepository;
import com.example.starter.transition.repo.RecycleQuotaRepository;
import com.example.starter.transition.repo.TransitionOrderRepository;
import com.example.starter.transition.repo.TransitionRollRepository;
import com.example.starter.transition.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 回收额度与投料服务：过渡卷回收重量登记、额度调减、投料申请与消耗查询。
 *
 * <p>规则（合成模型）：</p>
 * <ul>
 *     <li>每个未改判正品的已登记过渡卷至多登记一次可回收公斤数，正数最多三位小数；</li>
 *     <li>登记后只允许减少尚未消费的额度，不得把累计消费减掉；</li>
 *     <li>复议改判正品时未消费额度同步失效，已消费凭据保留；</li>
 *     <li>一次投料引用同一执行中订单、当前掺配版本与总浆量，回收合计须精确等于
 *     总浆量×目标比例/100，任一卷余额不足整单回滚。</li>
 * </ul>
 */
@Service
public class RecycleService {

    static final String BIZ_REGISTER_QUOTA = "REGISTER_RECYCLE_QUOTA";
    static final String BIZ_REDUCE_QUOTA = "REDUCE_RECYCLE_QUOTA";
    static final String BIZ_FEED = "SUBMIT_FEED";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransitionOrderRepository orderRepository;
    private final TransitionRollRepository rollRepository;
    private final BlendRatioVersionRepository blendRatioVersionRepository;
    private final RecycleQuotaRepository recycleQuotaRepository;
    private final FeedApplicationRepository feedApplicationRepository;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public RecycleService(TransitionOrderRepository orderRepository,
                          TransitionRollRepository rollRepository,
                          BlendRatioVersionRepository blendRatioVersionRepository,
                          RecycleQuotaRepository recycleQuotaRepository,
                          FeedApplicationRepository feedApplicationRepository,
                          IdempotencySupport idempotency,
                          Clock clock) {
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
        this.blendRatioVersionRepository = blendRatioVersionRepository;
        this.recycleQuotaRepository = recycleQuotaRepository;
        this.feedApplicationRepository = feedApplicationRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 回收重量登记（requestId 幂等）：每卷至多一次，正数最多三位小数；
     * 已改判正品的卷不能新增回收登记。与复议改判并发时通过纸卷行锁形成唯一次序。
     */
    @Transactional
    public IdempotentResult<RecycleQuotaView> registerQuota(RegisterRecycleQuotaRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotency.begin(request.requestId(), BIZ_REGISTER_QUOTA, fingerprint);
        if (existing != null) {
            return idempotency.replay(existing, new TypeReference<RecycleQuotaView>() {
            });
        }

        requirePositiveKg(request.recyclableKg(), "可回收公斤数");

        // 行锁读取纸卷：与复议改判（先更新纸卷行）互斥，保证登记与改判只形成一个一致次序。
        TransitionRoll roll = rollRepository.findByRollNoForUpdate(request.rollNo())
                .orElseThrow(() -> ApiException.notFound("过渡纸卷不存在: " + request.rollNo()));
        if ("UPGRADED".equals(roll.getVerdict())) {
            throw ApiException.conflict("该卷已改判正品，不能新增回收登记: " + request.rollNo());
        }

        RecycleQuota quota = new RecycleQuota();
        quota.setRollNo(roll.getRollNo());
        quota.setRegisteredKg(request.recyclableKg());
        quota.setRequestId(request.requestId());
        try {
            recycleQuotaRepository.insert(quota, now());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该卷已登记回收额度，每卷仅允许登记一次: " + request.rollNo());
        }

        RecycleQuotaView view = getQuota(roll.getRollNo());
        idempotency.complete(request.requestId(), roll.getRollNo(), view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /**
     * 额度调减（requestId 幂等）：expectedVersion 乐观版本控制（版本 1 起、成功加 1、过期 409）；
     * 只允许减少尚未消费的额度，新额度不得低于累计消费。
     */
    @Transactional
    public IdempotentResult<RecycleQuotaView> reduceQuota(String rollNo, ReduceRecycleQuotaRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId() + "|" + rollNo, request);
        IdempotentRequest existing = idempotency.begin(request.requestId(), BIZ_REDUCE_QUOTA, fingerprint);
        if (existing != null) {
            return idempotency.replay(existing, new TypeReference<RecycleQuotaView>() {
            });
        }

        BigDecimal newQuota = request.newQuotaKg();
        if (newQuota.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("新额度不得为负数: " + newQuota);
        }
        if (!TransitionRules.withinThreeDecimals(newQuota)) {
            throw ApiException.badRequest("新额度最多三位小数: " + newQuota);
        }

        RecycleQuota quota = recycleQuotaRepository.findByRollNo(rollNo)
                .orElseThrow(() -> ApiException.notFound("回收额度不存在: " + rollNo));
        if (!RecycleQuota.STATUS_ACTIVE.equals(quota.getStatus())) {
            throw ApiException.conflict("额度已失效（" + quota.getInvalidatedReason() + "），不得调减: " + rollNo);
        }
        if (request.expectedVersion() != quota.getVersion()) {
            throw ApiException.conflict("额度版本过期：期望 " + request.expectedVersion()
                    + "，当前 " + quota.getVersion() + "，请刷新后重试");
        }
        if (newQuota.compareTo(quota.getCurrentKg()) >= 0) {
            throw ApiException.conflict("只允许减少额度：新额度 " + newQuota
                    + " 须小于当前额度 " + quota.getCurrentKg());
        }
        if (newQuota.compareTo(quota.getConsumedKg()) < 0) {
            throw ApiException.conflict("不得把累计消费减掉：新额度 " + newQuota
                    + " 不得低于累计消费 " + quota.getConsumedKg());
        }

        if (recycleQuotaRepository.reduceWithVersion(rollNo, request.expectedVersion(),
                newQuota, now()) == 0) {
            // 并发变更导致版本过期或额度失效：本次调减随事务回滚。
            throw ApiException.conflict("额度已并发变更，调减失败: " + rollNo);
        }

        RecycleQuotaView view = getQuota(rollNo);
        idempotency.complete(request.requestId(), rollNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /**
     * 投料申请（requestId 幂等）：引用同一执行中订单、当前掺配版本与总浆量；
     * 回收合计精确等于总浆量×目标比例/100；所有卷同时扣额度，任一不足整单回滚。
     * 扣量按卷号排序，避免并发投料互相死锁。
     */
    @Transactional
    public IdempotentResult<FeedView> submitFeed(FeedRequest request) {
        String fingerprint = FingerprintUtil.sha256(request.requestId(), request);
        IdempotentRequest existing = idempotency.begin(request.requestId(), BIZ_FEED, fingerprint);
        if (existing != null) {
            return idempotency.replay(existing, new TypeReference<FeedView>() {
            });
        }

        requirePositiveKg(request.totalPulpKg(), "总浆量");
        if (request.items().isEmpty() || request.items().size() > 10) {
            throw ApiException.badRequest("一次投料须引用 1 至 10 个不同回收卷，实际 "
                    + request.items().size() + " 个");
        }
        List<String> rollNos = request.items().stream().map(FeedRequest.FeedItem::rollNo).toList();
        if (rollNos.stream().distinct().count() != rollNos.size()) {
            throw ApiException.conflict("同一次投料中回收卷号重复");
        }
        for (FeedRequest.FeedItem item : request.items()) {
            requirePositiveKg(item.kg(), "卷 " + item.rollNo() + " 投料公斤数");
        }

        TransitionOrder order = orderRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> ApiException.notFound("改品种单不存在: " + request.orderNo()));
        // 仅执行中订单可投料：暂停、归档及其他状态一律拒绝。
        if (!TransitionStatus.EXECUTED.equals(order.getStatus())) {
            throw ApiException.conflict("改品种单状态为 " + order.getStatus()
                    + "，仅执行中（EXECUTED）订单允许投料: " + request.orderNo());
        }
        if (order.getCurrentBlendVersion() == null) {
            throw ApiException.conflict("改品种单未携带掺配比例维度，不得投料: " + request.orderNo());
        }
        if (!request.blendVersionNo().equals(order.getCurrentBlendVersion())) {
            throw ApiException.conflict("掺配版本已换：引用版本 " + request.blendVersionNo()
                    + "，当前版本 " + order.getCurrentBlendVersion() + "，拒绝投料");
        }

        BlendRatioVersion version = blendRatioVersionRepository.findLatest(request.orderNo())
                .orElseThrow(() -> ApiException.conflict(
                        "改品种单未携带掺配比例维度，不得投料: " + request.orderNo()));
        BigDecimal required = TransitionRules.requiredRecycleKg(
                        request.totalPulpKg(), version.getTargetRatioPct())
                .orElseThrow(() -> ApiException.conflict("总浆量 " + request.totalPulpKg()
                        + " kg × 目标比例 " + version.getTargetRatioPct()
                        + "% 的计算结果超过三位小数精度，拒绝该组合"));
        BigDecimal actual = request.items().stream()
                .map(FeedRequest.FeedItem::kg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (actual.compareTo(required) != 0) {
            throw ApiException.conflict("回收合计 " + actual + " kg 须精确等于应投回收量 "
                    + required + " kg（总浆量×目标比例/100），拒绝该组合");
        }

        // 所有卷同时扣额度：条件更新（有效且余额充足）任一失败即抛 409，整单随事务回滚。
        LocalDateTime now = now();
        List<FeedRequest.FeedItem> sorted = request.items().stream()
                .sorted(Comparator.comparing(FeedRequest.FeedItem::rollNo))
                .toList();
        for (FeedRequest.FeedItem item : sorted) {
            if (recycleQuotaRepository.consume(item.rollNo(), item.kg(), now) == 0) {
                throw ApiException.conflict("回收卷额度未登记、已失效或余额不足: " + item.rollNo()
                        + "，整单回滚");
            }
        }

        String feedNo = newFeedNo();
        FeedApplication feed = new FeedApplication();
        feed.setFeedNo(feedNo);
        feed.setOrderNo(request.orderNo());
        feed.setBlendVersionNo(version.getVersionNo());
        feed.setTotalPulpKg(request.totalPulpKg());
        feed.setRequiredRecycleKg(required);
        feed.setActualRecycleKg(actual);
        feed.setRequestId(request.requestId());
        feed.setCreatedAt(now);
        feedApplicationRepository.insertApplication(feed);
        for (FeedRequest.FeedItem item : sorted) {
            FeedConsumption consumption = new FeedConsumption();
            consumption.setFeedNo(feedNo);
            consumption.setRollNo(item.rollNo());
            consumption.setKg(item.kg());
            consumption.setCreatedAt(now);
            feedApplicationRepository.insertConsumption(consumption);
        }

        FeedView view = toFeedView(feed, sorted);
        idempotency.complete(request.requestId(), feedNo, view);
        return new IdempotentResult<>(request.requestId(), false, view);
    }

    /** 回收额度查询：勾稽原额度、当前额度、累计消费与失效原因，含消费凭据。 */
    public RecycleQuotaView getQuota(String rollNo) {
        RecycleQuota quota = recycleQuotaRepository.findByRollNo(rollNo)
                .orElseThrow(() -> ApiException.notFound("回收额度不存在: " + rollNo));
        List<RecycleQuotaView.ConsumptionView> consumptions = feedApplicationRepository
                .findConsumptionsByRollNo(rollNo).stream()
                .map(c -> new RecycleQuotaView.ConsumptionView(
                        c.feedNo(), c.orderNo(), c.kg(), c.createdAt()))
                .toList();
        return new RecycleQuotaView(quota.getRollNo(), quota.getRegisteredKg(), quota.getCurrentKg(),
                quota.getConsumedKg(), quota.getCurrentKg().subtract(quota.getConsumedKg()),
                quota.getVersion(), quota.getStatus(), quota.getInvalidatedReason(),
                quota.getRequestId(), quota.getCreatedAt(), quota.getUpdatedAt(), consumptions);
    }

    // ------------------------------------------------------------------
    // 私有辅助方法（置于作用域末尾）
    // ------------------------------------------------------------------

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 正数且最多三位小数。 */
    private void requirePositiveKg(BigDecimal kg, String label) {
        if (kg.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest(label + "须为正数: " + kg);
        }
        if (!TransitionRules.withinThreeDecimals(kg)) {
            throw ApiException.badRequest(label + "最多三位小数: " + kg);
        }
    }

    private String newFeedNo() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return "FE-" + now().format(NO_FORMAT) + "-" + HexFormat.of().formatHex(bytes);
    }

    private FeedView toFeedView(FeedApplication feed, List<FeedRequest.FeedItem> items) {
        return new FeedView(feed.getFeedNo(), feed.getOrderNo(), feed.getBlendVersionNo(),
                feed.getTotalPulpKg(), feed.getRequiredRecycleKg(), feed.getActualRecycleKg(),
                items.stream().map(i -> new FeedView.FeedItemView(i.rollNo(), i.kg())).toList(),
                feed.getCreatedAt());
    }
}
