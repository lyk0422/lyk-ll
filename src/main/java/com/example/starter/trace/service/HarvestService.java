package com.example.starter.trace.service;

import com.example.starter.trace.api.TraceDtos.ContributionItem;
import com.example.starter.trace.api.TraceDtos.HarvestBatchDetailResponse;
import com.example.starter.trace.api.TraceDtos.HarvestBatchResponse;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.repo.HarvestRepository;
import com.example.starter.trace.repo.HarvestRepository.HarvestBatchRow;
import com.example.starter.trace.repo.TestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 采收批登记与查询：贡献比例保留两位小数且合计恰等于 100%。
 */
@Service
public class HarvestService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final HarvestRepository harvestRepository;
    private final TestRepository testRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public HarvestService(HarvestRepository harvestRepository, TestRepository testRepository,
                          IdempotencyService idempotencyService, Clock clock) {
        this.harvestRepository = harvestRepository;
        this.testRepository = testRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记采收批（幂等）。比例勾稽校验与写入在同一事务内完成。
     */
    @Transactional
    public HarvestBatchResponse register(RegisterHarvestBatchRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                request.batchNo(), request.floralPeriod(), canonicalContributions(request.contributions()));
        return idempotencyService.execute(request.requestId(), fingerprint,
                HarvestBatchResponse.class, () -> doRegister(request));
    }

    private HarvestBatchResponse doRegister(RegisterHarvestBatchRequest request) {
        validateRatios(request.contributions());
        for (ContributionItem item : request.contributions()) {
            if (!harvestRepository.apiaryExists(item.apiaryId())) {
                throw BusinessException.unprocessable("蜂场不存在: " + item.apiaryId());
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Long batchId;
        try {
            batchId = harvestRepository.insertBatch(request.batchNo(), request.floralPeriod(), now);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("采收批号已存在: " + request.batchNo());
        }
        try {
            for (ContributionItem item : request.contributions()) {
                harvestRepository.insertContribution(batchId, item.apiaryId(), item.ratio());
            }
        } catch (DuplicateKeyException e) {
            throw BusinessException.unprocessable("同一蜂场在批内重复贡献");
        }
        return new HarvestBatchResponse(batchId, request.batchNo(), request.floralPeriod(),
                "REGISTERED", harvestRepository.findContributions(batchId), now);
    }

    /**
     * 查询采收批详情，含全部快检 / 复检记录。
     */
    @Transactional(readOnly = true)
    public HarvestBatchDetailResponse detail(Long batchId) {
        HarvestBatchRow batch = harvestRepository.findBatchById(batchId)
                .orElseThrow(() -> BusinessException.notFound("采收批不存在: " + batchId));
        return new HarvestBatchDetailResponse(
                batch.id(), batch.batchNo(), batch.floralPeriod(), batch.status(),
                harvestRepository.findContributions(batchId),
                testRepository.findRapidTests(batchId),
                testRepository.findRetests(batchId),
                batch.createdAt());
    }

    /**
     * 比例勾稽：每项最多两位小数，合计恰等于 100.00。
     */
    private void validateRatios(List<ContributionItem> contributions) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ContributionItem item : contributions) {
            BigDecimal ratio = item.ratio();
            if (ratio.stripTrailingZeros().scale() > 2) {
                throw BusinessException.unprocessable("贡献比例最多保留两位小数: " + ratio);
            }
            sum = sum.add(ratio);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw BusinessException.unprocessable("贡献比例合计须恰等于 100.00，实际为 " + sum);
        }
    }

    private String canonicalContributions(List<ContributionItem> contributions) {
        return contributions.stream()
                .sorted(Comparator.comparing(ContributionItem::apiaryId))
                .map(item -> item.apiaryId() + "=" + item.ratio().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }
}
