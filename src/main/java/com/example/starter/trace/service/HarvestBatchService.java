package com.example.starter.trace.service;

import com.example.starter.trace.BatchStatus;
import com.example.starter.trace.BizException;
import com.example.starter.trace.RecallLinkOrigin;
import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.ApiaryRepository;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.RecallRepository;
import com.example.starter.trace.repo.ResidueTestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采收批登记与查询。贡献比例保留两位小数且合计恰等于 100.00。
 * 后来登记的批若含活动召回来源（同蜂场同花期），登记同事务自动建立隔离关联。
 */
@Service
public class HarvestBatchService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final HarvestBatchRepository batchRepository;
    private final ApiaryRepository apiaryRepository;
    private final ResidueTestRepository testRepository;
    private final RecallRepository recallRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public HarvestBatchService(HarvestBatchRepository batchRepository,
                               ApiaryRepository apiaryRepository,
                               ResidueTestRepository testRepository,
                               RecallRepository recallRepository,
                               IdempotencyService idempotency,
                               Clock clock) {
        this.batchRepository = batchRepository;
        this.apiaryRepository = apiaryRepository;
        this.testRepository = testRepository;
        this.recallRepository = recallRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等登记采收批。
     */
    @Transactional
    public HarvestBatchResponse createBatch(CreateHarvestBatchRequest request) {
        return idempotency.execute("CREATE_HARVEST_BATCH", request.requestId(), request,
                HarvestBatchResponse.class, () -> doCreate(request));
    }

    /**
     * 查询采收批详情，含贡献明细与全部检测记录（含被推翻的快检）。
     */
    @Transactional(readOnly = true)
    public HarvestBatchResponse getBatch(long batchId) {
        HarvestBatchRepository.BatchRow batch = batchRepository.findById(batchId)
                .orElseThrow(() -> BizException.notFound("采收批不存在: " + batchId));
        return toResponse(batch);
    }

    private HarvestBatchResponse doCreate(CreateHarvestBatchRequest request) {
        Set<Long> seenApiaries = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CreateHarvestBatchRequest.ContributionItem item : request.contributions()) {
            if (!seenApiaries.add(item.apiaryId())) {
                throw BizException.badRequest("同一蜂场在同一采收批中重复出现: " + item.apiaryId());
            }
            if (!apiaryRepository.existsById(item.apiaryId())) {
                throw BizException.badRequest("蜂场不存在: " + item.apiaryId());
            }
            total = total.add(item.ratio());
        }
        if (total.compareTo(HUNDRED) != 0) {
            throw BizException.badRequest("贡献比例合计须恰等于 100.00，实际为 " + total);
        }
        // 锁定贡献蜂场行：与召回创建在同一蜂场上串行化，保证活动召回不遗漏后到批次
        List<Long> apiaryIds = request.contributions().stream()
                .map(CreateHarvestBatchRequest.ContributionItem::apiaryId)
                .distinct().sorted().toList();
        for (Long apiaryId : apiaryIds) {
            apiaryRepository.findByIdForUpdate(apiaryId);
        }
        long batchId = batchRepository.insertBatch(request.batchNo(), request.flowerPeriod(),
                BatchStatus.REGISTERED, LocalDateTime.now(clock));
        for (CreateHarvestBatchRequest.ContributionItem item : request.contributions()) {
            batchRepository.insertContribution(batchId, item.apiaryId(), item.ratio());
        }
        // 后来登记的含活动召回来源的批自动受活动召回约束，追加 LATER 隔离关联
        LocalDateTime linkTime = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        for (CreateHarvestBatchRequest.ContributionItem item : request.contributions()) {
            recallRepository.findActiveBySource(item.apiaryId(), request.flowerPeriod())
                    .ifPresent(recall -> recallRepository.insertHarvestLink(
                            recall.id(), batchId, RecallLinkOrigin.LATER, linkTime));
        }
        return toResponse(batchRepository.findById(batchId).orElseThrow());
    }

    private HarvestBatchResponse toResponse(HarvestBatchRepository.BatchRow batch) {
        List<HarvestBatchResponse.ContributionView> contributions = batchRepository
                .findContributions(batch.id()).stream()
                .map(c -> {
                    var apiary = apiaryRepository.findById(c.apiaryId()).orElseThrow();
                    return new HarvestBatchResponse.ContributionView(
                            c.apiaryId(), apiary.code(), apiary.name(), c.ratio());
                })
                .toList();
        List<HarvestBatchResponse.TestView> tests = testRepository.findTestsByBatch(batch.id()).stream()
                .map(t -> new HarvestBatchResponse.TestView(t.id(), t.testType(), t.sampleNo(),
                        t.inhibitionRate(), t.conclusion().name(), t.createdAt()))
                .toList();
        return new HarvestBatchResponse(batch.id(), batch.batchNo(), batch.flowerPeriod(),
                batch.status().name(), batch.createdAt(), contributions, tests);
    }
}
