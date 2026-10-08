package com.example.starter.trace.service;

import com.example.starter.trace.BatchStatus;
import com.example.starter.trace.BizException;
import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.ApiaryRepository;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.ResidueTestRepository;
import com.example.starter.trace.repo.SourceRecallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 采收批登记与查询。贡献比例保留两位小数且合计恰等于 100.00。
 */
@Service
public class HarvestBatchService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final HarvestBatchRepository batchRepository;
    private final ApiaryRepository apiaryRepository;
    private final ResidueTestRepository testRepository;
    private final SourceRecallRepository recallRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public HarvestBatchService(HarvestBatchRepository batchRepository,
                               ApiaryRepository apiaryRepository,
                               ResidueTestRepository testRepository,
                               SourceRecallRepository recallRepository,
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
            total = total.add(item.ratio());
        }
        // 按蜂场 ID 升序加行锁，与来源召回创建串行化，避免快照与追加关联互相漏批
        for (Long apiaryId : new TreeSet<>(seenApiaries)) {
            apiaryRepository.findByIdForUpdate(apiaryId)
                    .orElseThrow(() -> BizException.badRequest("蜂场不存在: " + apiaryId));
        }
        if (total.compareTo(HUNDRED) != 0) {
            throw BizException.badRequest("贡献比例合计须恰等于 100.00，实际为 " + total);
        }
        long batchId = batchRepository.insertBatch(request.batchNo(), request.flowerPeriod(),
                BatchStatus.REGISTERED, LocalDateTime.now(clock));
        for (CreateHarvestBatchRequest.ContributionItem item : request.contributions()) {
            batchRepository.insertContribution(batchId, item.apiaryId(), item.ratio());
        }
        // 后来登记的含活动召回来源的批自动追加隔离关联（不影响首次冻结清单）
        LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        for (Long apiaryId : seenApiaries) {
            recallRepository.findActiveRecallId(apiaryId, request.flowerPeriod())
                    .ifPresent(recallId -> recallRepository.insertLink(recallId, batchId, false, nowUtc));
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
