package com.example.starter.trace.service;

import com.example.starter.trace.api.TraceDtos.ContributionView;
import com.example.starter.trace.api.TraceDtos.RapidTestRequest;
import com.example.starter.trace.api.TraceDtos.RapidTestResponse;
import com.example.starter.trace.api.TraceDtos.RetestRequest;
import com.example.starter.trace.api.TraceDtos.RetestResponse;
import com.example.starter.trace.repo.HarvestRepository;
import com.example.starter.trace.repo.HarvestRepository.HarvestBatchRow;
import com.example.starter.trace.repo.TestRepository;
import com.example.starter.trace.repo.TestRepository.TestChainRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 药残检测链：快检抑制率达到 50% 判疑似并冻结加工准入；
 * 复检须换样，合格解冻、不合格禁入加工；同蜂场同花期恰 3 批疑似触发预警。
 */
@Service
public class TestService {

    /** 疑似判定阈值：抑制率 >= 50.00 判疑似。 */
    private static final BigDecimal SUSPECT_THRESHOLD = new BigDecimal("50.00");

    /** 蜂场预警阈值：同蜂场同花期累计疑似批次数。 */
    private static final int ALERT_THRESHOLD = 3;

    private final HarvestRepository harvestRepository;
    private final TestRepository testRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TestService(HarvestRepository harvestRepository, TestRepository testRepository,
                       IdempotencyService idempotencyService, Clock clock) {
        this.harvestRepository = harvestRepository;
        this.testRepository = testRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 登记药残快检（幂等）。同批同时刻只允许一条检测链，靠行锁 + 链状态保证。
     */
    @Transactional
    public RapidTestResponse rapidTest(Long batchId, RapidTestRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(batchId), request.sampleNo(), request.inhibitionRate().toPlainString());
        return idempotencyService.execute(request.requestId(), fingerprint,
                RapidTestResponse.class, () -> doRapidTest(batchId, request));
    }

    private RapidTestResponse doRapidTest(Long batchId, RapidTestRequest request) {
        HarvestBatchRow batch = harvestRepository.lockBatchById(batchId)
                .orElseThrow(() -> BusinessException.notFound("采收批不存在: " + batchId));
        if ("REJECTED".equals(batch.status())) {
            throw BusinessException.unprocessable("复检不合格批不得再次送检: " + batchId);
        }
        TestChainRow chain = testRepository.findChainByBatchId(batchId).orElse(null);
        if (chain != null && "OPEN".equals(chain.status())) {
            throw BusinessException.conflict("同批同时刻只允许一条检测链: " + batchId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Long chainId;
        if (chain == null) {
            chainId = testRepository.insertChain(batchId, now);
        } else {
            testRepository.reopenChain(chain.id(), now);
            chainId = chain.id();
        }
        boolean suspect = request.inhibitionRate().compareTo(SUSPECT_THRESHOLD) >= 0;
        String result = suspect ? "SUSPECT" : "PASS";
        Long testId = testRepository.insertRapidTest(batchId, chainId, request.sampleNo(),
                request.inhibitionRate(), result, request.requestId(), now);
        String batchStatus;
        if (suspect) {
            harvestRepository.updateBatchStatus(batchId, "FROZEN");
            batchStatus = "FROZEN";
            triggerAlertsIfNeeded(batch);
        } else {
            harvestRepository.updateBatchStatus(batchId, "RELEASED");
            testRepository.closeChain(chainId, now);
            batchStatus = "RELEASED";
        }
        return new RapidTestResponse(testId, batchId, request.sampleNo(),
                request.inhibitionRate(), result, batchStatus, now);
    }

    /**
     * 登记定量复检（幂等）。复检须使用不同样品，同一样品重复送检拒绝。
     */
    @Transactional
    public RetestResponse retest(Long batchId, RetestRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(batchId), request.sampleNo(), request.result());
        return idempotencyService.execute(request.requestId(), fingerprint,
                RetestResponse.class, () -> doRetest(batchId, request));
    }

    private RetestResponse doRetest(Long batchId, RetestRequest request) {
        if (!"QUALIFIED".equals(request.result()) && !"UNQUALIFIED".equals(request.result())) {
            throw BusinessException.badRequest("复检结果仅允许 QUALIFIED / UNQUALIFIED: " + request.result());
        }
        HarvestBatchRow batch = harvestRepository.lockBatchById(batchId)
                .orElseThrow(() -> BusinessException.notFound("采收批不存在: " + batchId));
        TestChainRow chain = testRepository.findChainByBatchId(batchId)
                .filter(c -> "OPEN".equals(c.status()))
                .orElseThrow(() -> BusinessException.conflict("采收批无待复检的检测链: " + batchId));
        List<String> usedSamples = testRepository.findRapidSampleNos(chain.id());
        if (usedSamples.contains(request.sampleNo())) {
            throw BusinessException.unprocessable("复检须使用不同样品，同一样品重复送检拒绝: " + request.sampleNo());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Long retestId = testRepository.insertRetest(batchId, chain.id(), request.sampleNo(),
                request.result(), request.requestId(), now);
        testRepository.closeChain(chain.id(), now);
        String batchStatus;
        if ("QUALIFIED".equals(request.result())) {
            harvestRepository.updateBatchStatus(batchId, "RELEASED");
            batchStatus = "RELEASED";
        } else {
            harvestRepository.updateBatchStatus(batchId, "REJECTED");
            batchStatus = "REJECTED";
        }
        return new RetestResponse(retestId, batchId, request.sampleNo(), request.result(), batchStatus, now);
    }

    /**
     * 同蜂场同花期累计恰 3 批疑似时触发一次蜂场预警（唯一约束保证不重复）。
     */
    private void triggerAlertsIfNeeded(HarvestBatchRow batch) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (ContributionView contribution : harvestRepository.findContributions(batch.id())) {
            int suspectCount = testRepository.countSuspectBatches(
                    contribution.apiaryId(), batch.floralPeriod());
            if (suspectCount == ALERT_THRESHOLD) {
                testRepository.insertAlertIfAbsent(contribution.apiaryId(), batch.floralPeriod(),
                        suspectCount, now);
            }
        }
    }
}
