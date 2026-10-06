package com.example.starter.trace.service;

import com.example.starter.trace.BatchStatus;
import com.example.starter.trace.BizException;
import com.example.starter.trace.TestConclusion;
import com.example.starter.trace.dto.RapidTestRequest;
import com.example.starter.trace.dto.RetestRequest;
import com.example.starter.trace.dto.TestSubmissionResponse;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.ResidueTestRepository;
import com.example.starter.trace.repo.WarningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 药残检测服务：快检抑制率达到 50.00 判疑似并冻结；疑似批送定量复检且须换样；
 * 复检合格解除冻结，不合格维持冻结；同批同时刻仅允许一条检测链。
 */
@Service
public class ResidueTestService {

    /** 疑似判定阈值：抑制率达到 50.00% 即判疑似。 */
    static final BigDecimal SUSPECT_THRESHOLD = new BigDecimal("50.00");

    /** 同蜂场同花期疑似批预警阈值，恰 3 批触发。 */
    static final int WARNING_THRESHOLD = 3;

    private final HarvestBatchRepository batchRepository;
    private final ResidueTestRepository testRepository;
    private final WarningRepository warningRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ResidueTestService(HarvestBatchRepository batchRepository,
                              ResidueTestRepository testRepository,
                              WarningRepository warningRepository,
                              IdempotencyService idempotency,
                              Clock clock) {
        this.batchRepository = batchRepository;
        this.testRepository = testRepository;
        this.warningRepository = warningRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等提交药残快检。
     */
    @Transactional
    public TestSubmissionResponse submitRapidTest(long batchId, RapidTestRequest request) {
        return idempotency.execute("RAPID_TEST", request.requestId(), List.of(batchId, request),
                TestSubmissionResponse.class, () -> doRapidTest(batchId, request));
    }

    /**
     * 幂等提交定量复检。
     */
    @Transactional
    public TestSubmissionResponse submitRetest(long batchId, RetestRequest request) {
        return idempotency.execute("RETEST", request.requestId(), List.of(batchId, request),
                TestSubmissionResponse.class, () -> doRetest(batchId, request));
    }

    private TestSubmissionResponse doRapidTest(long batchId, RapidTestRequest request) {
        HarvestBatchRepository.BatchRow batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> BizException.notFound("采收批不存在: " + batchId));
        if (testRepository.findOpenChainId(batchId).isPresent()) {
            throw BizException.conflict("该采收批存在未闭环检测链，同批同时刻只允许一条检测链");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long chainId = testRepository.insertChain(batchId, now);
        if (!testRepository.tryOpenChainSlot(batchId, chainId)) {
            throw BizException.conflict("该采收批存在未闭环检测链，同批同时刻只允许一条检测链");
        }
        boolean suspected = request.inhibitionRate().compareTo(SUSPECT_THRESHOLD) >= 0;
        TestConclusion conclusion = suspected ? TestConclusion.SUSPECT : TestConclusion.PASS;
        long testId = testRepository.insertTest(chainId, batchId, "RAPID", request.sampleNo(),
                request.inhibitionRate(), conclusion, now);
        if (suspected) {
            batchRepository.updateStatus(batchId, BatchStatus.FROZEN);
            triggerWarningsIfNeeded(batch);
            // 疑似批等待复检，检测链保持 OPEN
        } else {
            batchRepository.updateStatus(batchId, BatchStatus.RELEASED);
            testRepository.closeChain(chainId, now);
            testRepository.releaseChainSlot(batchId);
        }
        BatchStatus after = suspected ? BatchStatus.FROZEN : BatchStatus.RELEASED;
        return new TestSubmissionResponse(testId, chainId, batchId, conclusion.name(),
                after.name(), request.inhibitionRate(), now);
    }

    private TestSubmissionResponse doRetest(long batchId, RetestRequest request) {
        HarvestBatchRepository.BatchRow batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> BizException.notFound("采收批不存在: " + batchId));
        Long chainId = testRepository.findOpenChainId(batchId)
                .orElseThrow(() -> BizException.conflict("该采收批无进行中的检测链，不能复检"));
        if (batch.status() != BatchStatus.FROZEN) {
            throw BizException.conflict("仅疑似冻结的采收批可以复检，当前状态: " + batch.status());
        }
        List<ResidueTestRepository.TestRow> chainTests = testRepository.findTestsByChain(chainId);
        boolean sampleReused = chainTests.stream()
                .anyMatch(t -> t.sampleNo().equals(request.sampleNo()));
        if (sampleReused) {
            throw BizException.conflict("复检须使用不同样品，同一样品重复送检拒绝: " + request.sampleNo());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        TestConclusion conclusion = request.passed() ? TestConclusion.OVERTURNED : TestConclusion.CONFIRMED;
        long testId = testRepository.insertTest(chainId, batchId, "RETEST", request.sampleNo(),
                request.inhibitionRate(), conclusion, now);
        BatchStatus after = request.passed() ? BatchStatus.RELEASED : BatchStatus.FROZEN;
        batchRepository.updateStatus(batchId, after);
        testRepository.closeChain(chainId, now);
        testRepository.releaseChainSlot(batchId);
        return new TestSubmissionResponse(testId, chainId, batchId, conclusion.name(),
                after.name(), request.inhibitionRate(), now);
    }

    /**
     * 疑似判定后检查该批每个贡献蜂场在同花期下的累计疑似批数，恰达到 3 批时触发蜂场预警。
     */
    private void triggerWarningsIfNeeded(HarvestBatchRepository.BatchRow batch) {
        LocalDateTime now = LocalDateTime.now(clock);
        batchRepository.findContributions(batch.id()).forEach(contribution -> {
            int suspected = testRepository.countSuspectedBatches(
                    contribution.apiaryId(), batch.flowerPeriod());
            if (suspected == WARNING_THRESHOLD) {
                warningRepository.insertIfAbsent(contribution.apiaryId(), batch.flowerPeriod(),
                        suspected, now);
            }
        });
    }
}
