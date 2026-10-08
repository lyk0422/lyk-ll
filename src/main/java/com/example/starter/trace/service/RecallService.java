package com.example.starter.trace.service;

import com.example.starter.trace.BizException;
import com.example.starter.trace.RecallStatus;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.ApiaryRepository;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.ProcessingBatchRepository;
import com.example.starter.trace.repo.SourceRecallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 来源召回服务：按蜂场加精确花期召回。创建时在同一事务内快照全部含该来源正贡献的
 * 采收批及其被引用加工批并原子建立隔离关联；后来登记的含该来源批由采收批登记流程追加关联。
 * 撤回须两名不同质检员持独立凭据确认，仅本召回的关联与标记失效。
 * 召回相关时刻一律以 UTC 记录。
 */
@Service
public class RecallService {

    private final SourceRecallRepository recallRepository;
    private final ApiaryRepository apiaryRepository;
    private final HarvestBatchRepository batchRepository;
    private final ProcessingBatchRepository processingRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public RecallService(SourceRecallRepository recallRepository,
                         ApiaryRepository apiaryRepository,
                         HarvestBatchRepository batchRepository,
                         ProcessingBatchRepository processingRepository,
                         IdempotencyService idempotency,
                         Clock clock) {
        this.recallRepository = recallRepository;
        this.apiaryRepository = apiaryRepository;
        this.batchRepository = batchRepository;
        this.processingRepository = processingRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等创建来源召回。同蜂场同花期最多一条 ACTIVE 召回。
     */
    @Transactional
    public RecallResponse createRecall(CreateRecallRequest request) {
        return idempotency.execute("CREATE_SOURCE_RECALL", request.requestId(), request,
                RecallResponse.class, () -> doCreate(request));
    }

    /**
     * 幂等撤回来源召回。携带 expectedVersion，过期 409；须两名不同质检员独立凭据。
     */
    @Transactional
    public RecallResponse withdrawRecall(long recallId, WithdrawRecallRequest request) {
        return idempotency.execute("WITHDRAW_SOURCE_RECALL", request.requestId(),
                List.of(recallId, request), RecallResponse.class, () -> doWithdraw(recallId, request));
    }

    /**
     * 查询召回详情，含首次冻结清单、后来追加关联与加工批隔离标记。
     */
    @Transactional(readOnly = true)
    public RecallResponse getRecall(long recallId) {
        SourceRecallRepository.RecallRow recall = recallRepository.findById(recallId)
                .orElseThrow(() -> BizException.notFound("召回单不存在: " + recallId));
        return toResponse(recall);
    }

    private RecallResponse doCreate(CreateRecallRequest request) {
        // 锁定蜂场行，与含该来源采收批的登记串行化，保证快照与追加关联不漏批
        apiaryRepository.findByIdForUpdate(request.apiaryId())
                .orElseThrow(() -> BizException.badRequest("蜂场不存在: " + request.apiaryId()));
        LocalDateTime now = utcNow();
        long recallId = recallRepository.insertRecall(request.apiaryId(), request.flowerPeriod(),
                request.reason(), request.credential(), request.inspector(), now);
        if (!recallRepository.tryOccupyActiveSlot(request.apiaryId(), request.flowerPeriod(), recallId)) {
            throw BizException.conflict("同蜂场同花期已存在活动召回: apiary="
                    + request.apiaryId() + ", flowerPeriod=" + request.flowerPeriod());
        }
        // 首次冻结清单：当时所有含该来源正贡献的采收批；逐批加行锁，与加工受理串行化
        List<Long> batchIds = recallRepository.findBatchIdsBySource(request.apiaryId(), request.flowerPeriod());
        for (Long batchId : batchIds) {
            batchRepository.findByIdForUpdate(batchId)
                    .orElseThrow(() -> BizException.conflict("采收批快照异常: " + batchId));
            recallRepository.insertLink(recallId, batchId, true, now);
        }
        // 已被引用的加工批仅加召回隔离标记，不删原加工证据
        for (Long processingBatchId : processingRepository.findProcessingIdsByHarvestBatchIds(batchIds)) {
            recallRepository.insertMark(recallId, processingBatchId, now);
        }
        return toResponse(recallRepository.findById(recallId).orElseThrow());
    }

    private RecallResponse doWithdraw(long recallId, WithdrawRecallRequest request) {
        if (request.firstInspector().equals(request.secondInspector())) {
            throw BizException.badRequest("撤回须两名不同质检员确认，质检员重复: " + request.firstInspector());
        }
        if (request.firstCredential().equals(request.secondCredential())) {
            throw BizException.badRequest("撤回须两份相互独立的凭据，凭据重复: " + request.firstCredential());
        }
        SourceRecallRepository.RecallRow recall = recallRepository.findByIdForUpdate(recallId)
                .orElseThrow(() -> BizException.notFound("召回单不存在: " + recallId));
        if (recall.status() != RecallStatus.ACTIVE) {
            throw BizException.conflict("召回单已撤回，不能重复撤回: " + recallId);
        }
        if (!recall.version().equals(request.expectedVersion())) {
            throw BizException.conflict("召回单版本已过期，期望 " + request.expectedVersion()
                    + "，当前 " + recall.version());
        }
        LocalDateTime now = utcNow();
        recallRepository.markWithdrawn(recallId, now);
        recallRepository.releaseActiveSlot(recall.apiaryId(), recall.flowerPeriod());
        // 仅本召回的关联与标记失效，其他召回的隔离不受影响
        recallRepository.releaseLinks(recallId);
        recallRepository.releaseMarks(recallId);
        return toResponse(recallRepository.findById(recallId).orElseThrow());
    }

    private RecallResponse toResponse(SourceRecallRepository.RecallRow recall) {
        var apiary = apiaryRepository.findById(recall.apiaryId()).orElseThrow();
        List<SourceRecallRepository.LinkRow> links = recallRepository.findLinks(recall.id());
        List<RecallResponse.LinkView> initial = links.stream()
                .filter(SourceRecallRepository.LinkRow::initialSnapshot)
                .map(this::toLinkView)
                .toList();
        List<RecallResponse.LinkView> appended = links.stream()
                .filter(l -> !l.initialSnapshot())
                .map(this::toLinkView)
                .toList();
        List<RecallResponse.ProcessingMarkView> marks = recallRepository.findMarks(recall.id()).stream()
                .map(m -> new RecallResponse.ProcessingMarkView(
                        m.processingBatchId(), m.processingBatchNo(), m.status(), m.createdAt()))
                .toList();
        return new RecallResponse(recall.id(), recall.apiaryId(), apiary.code(), recall.flowerPeriod(),
                recall.reason(), recall.status().name(), recall.version(),
                recall.createdAt(), recall.withdrawnAt(), initial, appended, marks);
    }

    private RecallResponse.LinkView toLinkView(SourceRecallRepository.LinkRow link) {
        return new RecallResponse.LinkView(link.id(), link.harvestBatchId(), link.batchNo(),
                link.status(), link.createdAt());
    }

    /**
     * 当前时刻的 UTC 表示；召回相关时间字段统一使用 UTC。
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
