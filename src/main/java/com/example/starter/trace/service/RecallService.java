package com.example.starter.trace.service;

import com.example.starter.trace.BizException;
import com.example.starter.trace.RecallLinkOrigin;
import com.example.starter.trace.RecallStatus;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import com.example.starter.trace.idem.IdempotencyService;
import com.example.starter.trace.repo.ApiaryRepository;
import com.example.starter.trace.repo.HarvestBatchRepository;
import com.example.starter.trace.repo.RecallRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 来源召回服务：创建时原子冻结当时所有含该来源正贡献的采收批及其被引用加工批清单；
 * 召回不是一次新的快检，不写入任何检测记录；撤回仅使该召回的关联失效。
 * 召回、关联、状态与幂等记录在同一事务内提交，时刻按 UTC 记录。
 */
@Service
public class RecallService {

    private final RecallRepository recallRepository;
    private final ApiaryRepository apiaryRepository;
    private final HarvestBatchRepository batchRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public RecallService(RecallRepository recallRepository,
                         ApiaryRepository apiaryRepository,
                         HarvestBatchRepository batchRepository,
                         IdempotencyService idempotency,
                         Clock clock) {
        this.recallRepository = recallRepository;
        this.apiaryRepository = apiaryRepository;
        this.batchRepository = batchRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等创建来源召回。指纹覆盖蜂场、花期、原因、凭据、人员与版本（新增恒为 1）。
     */
    @Transactional
    public RecallResponse createRecall(CreateRecallRequest request) {
        return idempotency.execute("CREATE_RECALL", request.requestId(), List.of(request, 1),
                RecallResponse.class, () -> doCreate(request));
    }

    /**
     * 幂等撤回来源召回。指纹覆盖召回单 ID、两名质检员、独立凭据与期望版本。
     */
    @Transactional
    public RecallResponse withdrawRecall(long recallId, WithdrawRecallRequest request) {
        return idempotency.execute("WITHDRAW_RECALL", request.requestId(), List.of(recallId, request),
                RecallResponse.class, () -> doWithdraw(recallId, request));
    }

    /**
     * 查询召回详情，展示首次冻结清单与后来追加关联（含已失效关联的当前状态）。
     */
    @Transactional(readOnly = true)
    public RecallResponse getRecall(long recallId) {
        RecallRepository.RecallRow recall = recallRepository.findById(recallId)
                .orElseThrow(() -> BizException.notFound("召回单不存在: " + recallId));
        return toResponse(recall);
    }

    private RecallResponse doCreate(CreateRecallRequest request) {
        // 锁蜂场行：与采收批登记在同一蜂场上串行化，保证后到批次可靠挂接；同时校验蜂场存在
        apiaryRepository.findByIdForUpdate(request.apiaryId())
                .orElseThrow(() -> BizException.notFound("蜂场不存在: " + request.apiaryId()));
        recallRepository.findActiveBySource(request.apiaryId(), request.flowerPeriod())
                .ifPresent(existing -> {
                    throw BizException.conflict("同蜂场同花期已存在活动召回: " + existing.id());
                });
        LocalDateTime now = utcNow();
        long recallId;
        try {
            recallId = recallRepository.insertRecall(request.apiaryId(), request.flowerPeriod(),
                    request.reason(), request.credential(), request.createdBy(), now);
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("同蜂场同花期已存在活动召回");
        }
        // 冻结当时所有含该来源正贡献的采收批及其被引用加工批清单，原子建立隔离关联
        for (Long batchId : recallRepository.findSourceHarvestBatchIds(
                request.apiaryId(), request.flowerPeriod())) {
            // 锁采收批行：与加工受理串行化，加工引用插入前必须看到本召回的隔离关联
            batchRepository.findByIdForUpdate(batchId)
                    .orElseThrow(() -> BizException.notFound("采收批不存在: " + batchId));
            recallRepository.insertHarvestLink(recallId, batchId, RecallLinkOrigin.INITIAL, now);
            for (Long processingBatchId : recallRepository.findProcessingBatchIdsByHarvestBatch(batchId)) {
                recallRepository.insertProcessingLink(recallId, processingBatchId,
                        RecallLinkOrigin.INITIAL, now);
            }
        }
        return toResponse(recallRepository.findById(recallId).orElseThrow());
    }

    private RecallResponse doWithdraw(long recallId, WithdrawRecallRequest request) {
        if (request.inspectorPrimary().equals(request.inspectorSecondary())) {
            throw BizException.badRequest("撤回须由两名不同质检员确认");
        }
        if (request.credentialPrimary().equals(request.credentialSecondary())) {
            throw BizException.badRequest("撤回须使用相互独立的凭据");
        }
        RecallRepository.RecallRow recall = recallRepository.findByIdForUpdate(recallId)
                .orElseThrow(() -> BizException.notFound("召回单不存在: " + recallId));
        if (recall.status() != RecallStatus.ACTIVE) {
            throw BizException.conflict("召回单已撤回，不能重复撤回: " + recallId);
        }
        if (recall.version().longValue() != request.expectedVersion()) {
            throw BizException.conflict("召回单版本过期，当前版本 " + recall.version()
                    + "，请求版本 " + request.expectedVersion());
        }
        boolean withdrawn = recallRepository.withdraw(recallId, request.expectedVersion(),
                request.inspectorPrimary(), request.inspectorSecondary(),
                request.credentialPrimary(), request.credentialSecondary(), utcNow());
        if (!withdrawn) {
            throw BizException.conflict("召回单版本过期或已撤回: " + recallId);
        }
        // 仅该召回的关联失效，其他召回的隔离关联不受影响
        recallRepository.releaseLinks(recallId);
        return toResponse(recallRepository.findById(recallId).orElseThrow());
    }

    private RecallResponse toResponse(RecallRepository.RecallRow recall) {
        var apiary = apiaryRepository.findById(recall.apiaryId()).orElseThrow();
        List<RecallResponse.HarvestLinkView> harvestLinks = recallRepository
                .findHarvestLinks(recall.id()).stream()
                .map(l -> new RecallResponse.HarvestLinkView(l.harvestBatchId(), l.batchNo(),
                        l.origin().name(), l.status().name(), l.createdAt()))
                .toList();
        List<RecallResponse.ProcessingLinkView> processingLinks = recallRepository
                .findProcessingLinks(recall.id()).stream()
                .map(l -> new RecallResponse.ProcessingLinkView(l.processingBatchId(), l.batchNo(),
                        l.origin().name(), l.status().name(), l.createdAt()))
                .toList();
        return new RecallResponse(recall.id(), recall.apiaryId(), apiary.code(), apiary.name(),
                recall.flowerPeriod(), recall.reason(), recall.status().name(), recall.version(),
                recall.createdBy(), recall.createdAt(), recall.withdrawnAt(),
                harvestLinks, processingLinks);
    }

    /**
     * 召回域时刻统一按 UTC 记录。
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
