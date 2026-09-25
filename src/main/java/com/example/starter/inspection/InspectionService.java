package com.example.starter.inspection;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.alert.AlertService;
import com.example.starter.api.ConflictException;
import com.example.starter.api.Fingerprints;
import com.example.starter.api.NotFoundException;
import com.example.starter.batch.Batch;
import com.example.starter.batch.BatchRepository;
import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.SamplingMode;
import com.example.starter.domain.TestVerdict;
import com.example.starter.event.LateEventRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抽样与快检服务：按（批次＋项目）去重，重复送检拒绝；
 * 快检判定与准入放行在同一事务内完成；同一批次同时刻只允许一条检测链。
 */
@Service
public class InspectionService {

    private final BatchRepository batchRepository;
    private final SampleRepository sampleRepository;
    private final RapidTestRepository rapidTestRepository;
    private final LateEventRepository lateEventRepository;
    private final AlertService alertService;
    private final Clock clock;

    public InspectionService(BatchRepository batchRepository, SampleRepository sampleRepository,
                             RapidTestRepository rapidTestRepository,
                             LateEventRepository lateEventRepository,
                             AlertService alertService, Clock clock) {
        this.batchRepository = batchRepository;
        this.sampleRepository = sampleRepository;
        this.rapidTestRepository = rapidTestRepository;
        this.lateEventRepository = lateEventRepository;
        this.alertService = alertService;
        this.clock = clock;
    }

    /**
     * 抽样登记。幂等重放返回首次结果；重复送检（同批次同项目）拒绝；
     * 产地预警期间该产地同品种批次按加严抽检登记。
     */
    @Transactional
    public SampleResponse sample(long batchId, SampleRequest request) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchId));
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(batchId), request.item());
        var replay = sampleRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            if (!replay.get().fingerprint().equals(fingerprint)) {
                throw new ConflictException("同一 requestId 业务参数不一致，拒绝重放");
            }
            return SampleResponse.from(replay.get());
        }
        if (batch.status().isTerminal()) {
            throw new ConflictException("批次已结案（终态），拒绝抽样");
        }
        if (batch.status() == BatchStatus.SUSPECTED) {
            throw new ConflictException("批次疑似冻结中，存在进行中的检测链，拒绝新增抽样");
        }
        if (sampleRepository.findByBatchAndItem(batchId, request.item()).isPresent()) {
            throw new ConflictException("重复送检拒绝：该批次该项目已存在抽样记录");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        SamplingMode mode = alertService.isAlertActive(batch.origin(), batch.variety(), now.toLocalDate())
                ? SamplingMode.ENHANCED : SamplingMode.NORMAL;
        try {
            long id = sampleRepository.insert(request.requestId(), fingerprint, batchId,
                    request.item(), mode, now);
            return new SampleResponse(id, batchId, request.item(), mode, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("重复送检拒绝：该批次该项目已存在抽样记录");
        }
    }

    /**
     * 快检判定。抑制率 &gt;= 50%（恰 50% 亦判疑似）判疑似超标并冻结准入、触发产地预警累计；
     * 合格则判定与放行在同一事务内完成。结案后迟到结果只记迟到事件。
     */
    @Transactional
    public RapidTestResponse rapidTest(long batchId, RapidTestRequest request) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchId));
        String fingerprint = Fingerprints.of(request.requestId(), String.valueOf(batchId),
                request.item(), request.inhibitionRate().toPlainString());
        var replay = rapidTestRepository.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            if (!replay.get().fingerprint().equals(fingerprint)) {
                throw new ConflictException("同一 requestId 业务参数不一致，拒绝重放");
            }
            return RapidTestResponse.from(replay.get(), batch.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (batch.status().isTerminal()) {
            lateEventRepository.insert(batchId, "RAPID_TEST",
                    "结案后迟到快检结果，requestId=" + request.requestId(), now);
            return RapidTestResponse.late(batchId, batch.status());
        }
        if (batch.status() == BatchStatus.SUSPECTED) {
            throw new ConflictException("该批次存在进行中的检测链（疑似待复检），拒绝新的快检");
        }
        if (sampleRepository.findByBatchAndItem(batchId, request.item()).isEmpty()) {
            throw new ConflictException("未抽样不能快检：该批次该项目缺少抽样记录");
        }
        if (rapidTestRepository.findByBatchAndItem(batchId, request.item()).isPresent()) {
            throw new ConflictException("重复送检拒绝：该批次该项目已存在快检记录");
        }
        TestVerdict verdict = TestVerdict.of(request.inhibitionRate());
        long testId;
        try {
            testId = rapidTestRepository.insert(request.requestId(), fingerprint, batchId,
                    request.item(), request.inhibitionRate(), verdict, now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("重复送检拒绝：该批次该项目已存在快检记录");
        }
        BatchStatus newStatus;
        if (verdict == TestVerdict.SUSPECTED) {
            transition(batch, BatchStatus.REGISTERED, BatchStatus.SUSPECTED, now);
            alertService.onSuspected(batch, now);
            newStatus = BatchStatus.SUSPECTED;
        } else {
            // 判定合格与准入放行在同一事务内完成，不得未判定先放行
            transition(batch, BatchStatus.REGISTERED, BatchStatus.RELEASED, now);
            newStatus = BatchStatus.RELEASED;
        }
        return new RapidTestResponse(testId, batchId, request.item(), request.inhibitionRate(),
                verdict, newStatus, false, now);
    }

    /**
     * 条件状态流转：当前状态不符时抛冲突并回滚本事务内已写入的检测记录。
     */
    private void transition(Batch batch, BatchStatus expected, BatchStatus target, LocalDateTime now) {
        if (batchRepository.transitionStatus(batch.id(), expected, target, now) == 0) {
            throw new ConflictException("批次状态已变更，检测链冲突");
        }
    }
}
