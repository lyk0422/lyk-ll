package com.example.starter.blend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.blend.BlendContracts.BatchView;
import com.example.starter.blend.BlendContracts.ComponentQty;
import com.example.starter.blend.BlendContracts.CreateOrderRequest;
import com.example.starter.blend.BlendContracts.LabSampleRequest;
import com.example.starter.blend.BlendContracts.LabSampleResponse;
import com.example.starter.blend.BlendContracts.MovementView;
import com.example.starter.blend.BlendContracts.OrderDetailResponse;
import com.example.starter.blend.BlendContracts.OrderResponse;
import com.example.starter.blend.BlendContracts.ReblendRequest;
import com.example.starter.blend.BlendContracts.ReblendResponse;
import com.example.starter.blend.BlendContracts.ReleaseResponse;
import com.example.starter.blend.BlendContracts.SampleView;
import com.example.starter.blend.BlendModel.ComponentInput;
import com.example.starter.blend.BlendModel.EdgeHint;
import com.example.starter.blend.BlendModel.IndicatorSet;
import com.example.starter.blend.BlendRepository.BatchRow;
import com.example.starter.blend.BlendRepository.OrderRow;
import com.example.starter.blend.BlendRepository.SampleRow;
import com.example.starter.blend.BlendRepository.SpecRow;
import com.example.starter.blend.BlendRepository.TankRow;

/**
 * 调合核心业务：配方下达（切罐+扣量原子完成）、双采样化验判定、返调一次、放行封账。
 * 调合单与化验提交按 requestId 幂等：指纹覆盖 requestId 与全部业务字段，
 * 同键同参返回首次结果，同键异参返回 409 且不占用罐量。
 * 同一调合罐同时刻只允许一张执行中调合单（blend_tank_lock 主键保证并发唯一）。
 */
@Service
public class BlendOrderService {

    static final String STATUS_EXECUTING = "EXECUTING";
    static final String STATUS_RELEASED = "RELEASED";
    static final String STATUS_OFF_SPEC = "OFF_SPEC";

    static final String BATCH_ACTIVE = "ACTIVE";
    static final String BATCH_FAILED = "FAILED";
    static final String BATCH_SUPERSEDED = "SUPERSEDED";
    static final String BATCH_PASSED = "PASSED";
    static final String BATCH_RELEASED = "RELEASED";

    private final BlendRepository repo;
    private final TransactionTemplate tx;

    public BlendOrderService(BlendRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 配方下达：校验规格与罐余量后，在同一事务内占用调合罐、扣减组分库存、
     * 写入切罐流水与首批次；任一组分不足则整单不下达且库存无变动。
     */
    public OrderResponse createOrder(CreateOrderRequest req) {
        String fingerprint = fingerprint(req.requestId(), req.blendTankCode(), req.productCode(),
                req.targetQtyKg().stripTrailingZeros().toPlainString(), canonical(req.components()));
        var existing = repo.findOrderByRequestId(req.requestId());
        if (existing.isPresent()) {
            OrderRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝占用罐量");
            }
            return toOrderResponse(row, true);
        }
        try {
            return tx.execute(status -> doCreateOrder(req, fingerprint));
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 或同调合罐冲突：优先按幂等重放处理
            var raced = repo.findOrderByRequestId(req.requestId());
            if (raced.isPresent()) {
                if (!raced.get().fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("requestId 已存在且业务参数不一致，拒绝占用罐量");
                }
                return toOrderResponse(raced.get(), true);
            }
            throw ApiException.conflict("调合罐 " + req.blendTankCode() + " 已存在执行中调合单");
        }
    }

    private OrderResponse doCreateOrder(CreateOrderRequest req, String fingerprint) {
        SpecRow spec = repo.findSpec(req.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + req.productCode()));
        List<ComponentInput> inputs = new ArrayList<>();
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        Map<String, TankRow> tanks = new LinkedHashMap<>();
        for (ComponentQty c : req.components()) {
            TankRow tank = repo.findTank(c.tankCode())
                    .orElseThrow(() -> ApiException.unprocessable("未知组分罐: " + c.tankCode()));
            tanks.put(c.tankCode(), tank);
            inputs.add(new ComponentInput(c.tankCode(), c.qtyKg(), tank.octane(), tank.sulfur(), tank.rvp()));
            sum = sum.add(c.qtyKg());
        }
        if (sum.compareTo(req.targetQtyKg()) != 0) {
            throw ApiException.unprocessable("组分切罐量之和 " + sum.stripTrailingZeros().toPlainString()
                    + " 与目标调合量 " + req.targetQtyKg().stripTrailingZeros().toPlainString() + " 不一致");
        }
        IndicatorSet indicators = BlendModel.blend(inputs);

        long orderId = repo.insertOrder(req.requestId(), fingerprint, req.blendTankCode(),
                req.productCode(), req.targetQtyKg(), STATUS_EXECUTING);
        repo.acquireTankLock(req.blendTankCode(), orderId);
        long batchId = repo.insertBatch(orderId, 1, null, req.requestId(), fingerprint,
                BATCH_ACTIVE, req.targetQtyKg(), indicators);
        for (ComponentQty c : req.components()) {
            if (repo.deductTankStock(c.tankCode(), c.qtyKg()) == 0) {
                throw ApiException.unprocessable("组分罐 " + c.tankCode() + " 余量不足，整单不予下达");
            }
            repo.insertMovement(orderId, batchId, c.tankCode(), c.qtyKg());
            repo.insertComponent(orderId, batchId, c.tankCode(), c.qtyKg());
        }
        return new OrderResponse(orderId, req.requestId(), STATUS_EXECUTING, req.blendTankCode(),
                req.productCode(), req.targetQtyKg(), batchId, indicators,
                BlendModel.edgeHints(indicators, spec.toLimits()), false);
    }

    /**
     * 化验提交：双采样点均合格方可放行；首样不合格批次标记 FAILED 等待返调；
     * 返调批次仍不合格则整单转 OFF_SPEC 终态；封账后到达的数据只记迟到事件。
     */
    public LabSampleResponse submitLabSample(long orderId, LabSampleRequest req) {
        String fingerprint = fingerprint(req.requestId(), String.valueOf(orderId),
                String.valueOf(req.samplePoint()));
        var existing = repo.findSampleByRequestId(req.requestId());
        if (existing.isPresent()) {
            SampleRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致");
            }
            return toSampleResponse(row, true);
        }
        try {
            return tx.execute(status -> doSubmitSample(orderId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            var raced = repo.findSampleByRequestId(req.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toSampleResponse(raced.get(), true);
            }
            throw ApiException.conflict("该批次采样点 " + req.samplePoint() + " 已提交化验数据");
        }
    }

    private LabSampleResponse doSubmitSample(long orderId, LabSampleRequest req, String fingerprint) {
        OrderRow order = repo.findOrderForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("调合单不存在: " + orderId));
        BatchRow batch = latestBatch(orderId);
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        IndicatorSet indicators = new IndicatorSet(batch.octane(), batch.sulfur(), batch.rvp());
        boolean passed = BlendModel.passes(indicators, spec.toLimits());

        if (STATUS_RELEASED.equals(order.status())) {
            long sampleId = repo.insertSample(req.requestId(), fingerprint, orderId, batch.id(),
                    req.samplePoint(), indicators, passed, true);
            return new LabSampleResponse(sampleId, req.requestId(), orderId, batch.id(),
                    req.samplePoint(), indicators, passed, true, order.status(), batch.status(),
                    BlendModel.edgeHints(indicators, spec.toLimits()), false);
        }
        if (STATUS_OFF_SPEC.equals(order.status())) {
            throw ApiException.conflict("调合单已转不合格品终态，不再接收化验数据");
        }
        if (!BATCH_ACTIVE.equals(batch.status())) {
            throw ApiException.conflict("批次状态 " + batch.status() + " 不允许继续采样");
        }

        long sampleId = repo.insertSample(req.requestId(), fingerprint, orderId, batch.id(),
                req.samplePoint(), indicators, passed, false);
        String batchStatus = BATCH_ACTIVE;
        String orderStatus = order.status();
        if (!passed) {
            repo.updateBatchStatus(batch.id(), BATCH_FAILED);
            batchStatus = BATCH_FAILED;
            if (batch.parentBatchId() != null) {
                // 返调批次仍不合格：整罐转不合格品终态，释放调合罐
                repo.updateOrderStatus(orderId, STATUS_OFF_SPEC, null);
                repo.releaseTankLock(order.blendTankCode());
                orderStatus = STATUS_OFF_SPEC;
            }
        } else {
            List<SampleRow> samples = repo.findBatchSamples(batch.id());
            boolean bothPassed = samples.size() == 2 && samples.stream().allMatch(SampleRow::passed);
            if (bothPassed) {
                repo.updateBatchStatus(batch.id(), BATCH_PASSED);
                batchStatus = BATCH_PASSED;
            }
        }
        return new LabSampleResponse(sampleId, req.requestId(), orderId, batch.id(),
                req.samplePoint(), indicators, passed, false, orderStatus, batchStatus,
                BlendModel.edgeHints(indicators, spec.toLimits()), false);
    }

    /**
     * 返调：仅首样不合格允许一次；生成引用原批次的新批次，追加组分切罐与扣量同事务完成；
     * 返调后重走双采样判定。
     */
    public ReblendResponse reblend(long orderId, ReblendRequest req) {
        String fingerprint = fingerprint(req.requestId(), String.valueOf(orderId),
                canonical(req.additionalComponents()));
        var existing = repo.findBatchByRequestId(req.requestId());
        if (existing.isPresent()) {
            BatchRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务参数不一致");
            }
            return toReblendResponse(orderId, row, true);
        }
        try {
            return tx.execute(status -> doReblend(orderId, req, fingerprint));
        } catch (DuplicateKeyException e) {
            var raced = repo.findBatchByRequestId(req.requestId());
            if (raced.isPresent() && raced.get().fingerprint().equals(fingerprint)) {
                return toReblendResponse(orderId, raced.get(), true);
            }
            throw ApiException.conflict("返调请求冲突，请核对幂等键与参数");
        }
    }

    private ReblendResponse doReblend(long orderId, ReblendRequest req, String fingerprint) {
        OrderRow order = repo.findOrderForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("调合单不存在: " + orderId));
        if (!STATUS_EXECUTING.equals(order.status())) {
            throw ApiException.conflict("调合单状态 " + order.status() + " 不允许返调");
        }
        BatchRow failed = latestBatch(orderId);
        if (!BATCH_FAILED.equals(failed.status()) || failed.parentBatchId() != null) {
            throw ApiException.conflict("仅首样不合格允许返调一次");
        }
        // 合并原批次组分与追加组分，按累计调合量复算指标
        Map<String, java.math.BigDecimal> merged = new LinkedHashMap<>();
        repo.findComponents(orderId).stream()
                .filter(c -> c.batchId() == failed.id())
                .forEach(c -> merged.merge(c.tankCode(), c.qtyKg(), java.math.BigDecimal::add));
        for (ComponentQty c : req.additionalComponents()) {
            merged.merge(c.tankCode(), c.qtyKg(), java.math.BigDecimal::add);
        }
        List<ComponentInput> inputs = new ArrayList<>();
        for (var e : merged.entrySet()) {
            TankRow tank = repo.findTank(e.getKey())
                    .orElseThrow(() -> ApiException.unprocessable("未知组分罐: " + e.getKey()));
            inputs.add(new ComponentInput(e.getKey(), e.getValue(), tank.octane(), tank.sulfur(), tank.rvp()));
        }
        IndicatorSet indicators = BlendModel.blend(inputs);
        java.math.BigDecimal totalQty = merged.values().stream()
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        long newBatchId = repo.insertBatch(orderId, failed.batchNo() + 1, failed.id(),
                req.requestId(), fingerprint, BATCH_ACTIVE, totalQty, indicators);
        for (ComponentQty c : req.additionalComponents()) {
            if (repo.deductTankStock(c.tankCode(), c.qtyKg()) == 0) {
                throw ApiException.unprocessable("组分罐 " + c.tankCode() + " 余量不足，返调不予执行");
            }
            repo.insertMovement(orderId, newBatchId, c.tankCode(), c.qtyKg());
            repo.insertComponent(orderId, newBatchId, c.tankCode(), c.qtyKg());
        }
        repo.updateBatchStatus(failed.id(), BATCH_SUPERSEDED);
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        return new ReblendResponse(orderId, newBatchId, failed.batchNo() + 1, failed.id(),
                STATUS_EXECUTING, indicators, BlendModel.edgeHints(indicators, spec.toLimits()), false);
    }

    /**
     * 放行封账：要求最新批次双采样均合格；封账为终态，重复封账返回幂等成功。
     */
    public ReleaseResponse release(long orderId) {
        return tx.execute(status -> {
            OrderRow order = repo.findOrderForUpdate(orderId)
                    .orElseThrow(() -> ApiException.notFound("调合单不存在: " + orderId));
            if (STATUS_RELEASED.equals(order.status())) {
                return new ReleaseResponse(orderId, STATUS_RELEASED, order.releasedAt(), true);
            }
            if (STATUS_OFF_SPEC.equals(order.status())) {
                throw ApiException.conflict("调合单已转不合格品终态，不得放行");
            }
            BatchRow batch = latestBatch(orderId);
            if (!BATCH_PASSED.equals(batch.status())) {
                throw ApiException.conflict("双采样未全部合格，不得放行封账");
            }
            Instant now = Instant.now();
            repo.updateOrderStatus(orderId, STATUS_RELEASED, now);
            repo.updateBatchStatus(batch.id(), BATCH_RELEASED);
            repo.releaseTankLock(order.blendTankCode());
            // 以数据库回读值为准，保证重复封账返回的封账时间一致
            Instant stored = repo.findOrder(orderId).map(OrderRow::releasedAt).orElse(now);
            return new ReleaseResponse(orderId, STATUS_RELEASED, stored, false);
        });
    }

    /**
     * 调合单详情：指标、卡边率与批次链均可复算，重复查询结果稳定。
     */
    public OrderDetailResponse getDetail(long orderId) {
        OrderRow order = repo.findOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("调合单不存在: " + orderId));
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        List<BatchRow> batches = repo.findBatches(orderId);
        BatchRow latest = batches.get(batches.size() - 1);
        IndicatorSet indicators = new IndicatorSet(latest.octane(), latest.sulfur(), latest.rvp());
        List<BatchView> batchViews = batches.stream()
                .map(b -> new BatchView(b.id(), b.batchNo(), b.parentBatchId(), b.status(),
                        b.totalQtyKg(), new IndicatorSet(b.octane(), b.sulfur(), b.rvp())))
                .toList();
        List<SampleView> sampleViews = repo.findSamples(orderId).stream()
                .map(s -> new SampleView(s.id(), s.batchId(), s.samplePoint(),
                        new IndicatorSet(s.octane(), s.sulfur(), s.rvp()), s.passed(), s.late()))
                .toList();
        List<MovementView> movementViews = repo.findMovements(orderId).stream()
                .map(m -> new MovementView(m.batchId(), m.tankCode(), m.qtyKg()))
                .toList();
        return new OrderDetailResponse(order.id(), order.requestId(), order.status(),
                order.blendTankCode(), order.productCode(), order.targetQtyKg(),
                order.createdAt(), order.releasedAt(), indicators,
                BlendModel.edgeHints(indicators, spec.toLimits()),
                batchViews, sampleViews, movementViews);
    }

    private BatchRow latestBatch(long orderId) {
        List<BatchRow> batches = repo.findBatches(orderId);
        if (batches.isEmpty()) {
            throw ApiException.conflict("调合单缺少调合批次: " + orderId);
        }
        return batches.get(batches.size() - 1);
    }

    private OrderResponse toOrderResponse(OrderRow order, boolean replayed) {
        BatchRow latest = latestBatch(order.id());
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        IndicatorSet indicators = new IndicatorSet(latest.octane(), latest.sulfur(), latest.rvp());
        return new OrderResponse(order.id(), order.requestId(), order.status(), order.blendTankCode(),
                order.productCode(), order.targetQtyKg(), latest.id(), indicators,
                BlendModel.edgeHints(indicators, spec.toLimits()), replayed);
    }

    private LabSampleResponse toSampleResponse(SampleRow sample, boolean replayed) {
        OrderRow order = repo.findOrder(sample.orderId())
                .orElseThrow(() -> ApiException.notFound("调合单不存在: " + sample.orderId()));
        BatchRow batch = repo.findBatches(sample.orderId()).stream()
                .filter(b -> b.id() == sample.batchId())
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("样本所属批次不存在"));
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        IndicatorSet indicators = new IndicatorSet(sample.octane(), sample.sulfur(), sample.rvp());
        return new LabSampleResponse(sample.id(), sample.requestId(), sample.orderId(), sample.batchId(),
                sample.samplePoint(), indicators, sample.passed(), sample.late(),
                order.status(), batch.status(),
                BlendModel.edgeHints(indicators, spec.toLimits()), replayed);
    }

    private ReblendResponse toReblendResponse(long orderId, BatchRow batch, boolean replayed) {
        OrderRow order = repo.findOrder(orderId)
                .orElseThrow(() -> ApiException.notFound("调合单不存在: " + orderId));
        SpecRow spec = repo.findSpec(order.productCode())
                .orElseThrow(() -> ApiException.unprocessable("未知成品牌号: " + order.productCode()));
        IndicatorSet indicators = new IndicatorSet(batch.octane(), batch.sulfur(), batch.rvp());
        return new ReblendResponse(orderId, batch.id(), batch.batchNo(),
                batch.parentBatchId() == null ? 0L : batch.parentBatchId(), order.status(), indicators,
                BlendModel.edgeHints(indicators, spec.toLimits()), replayed);
    }

    /** 组分明细规范化：按罐号排序、数值去尾零，保证指纹稳定。 */
    private static String canonical(List<ComponentQty> components) {
        return components.stream()
                .sorted(Comparator.comparing(ComponentQty::tankCode))
                .map(c -> c.tankCode() + ":" + c.qtyKg().stripTrailingZeros().toPlainString())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /** 业务指纹：SHA-256(requestId 与全部业务字段)。 */
    static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
