package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.ReceivingDtos.MergeItemResponse;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiveRequest;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptResponse;
import com.example.starter.fermentation.api.ReceivingDtos.ReceivingTankResponse;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterSourceLiquorRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterTankRequest;
import com.example.starter.fermentation.api.ReceivingDtos.SourceLiquorResponse;
import com.example.starter.fermentation.api.ReceivingDtos.UpdateTankCapacityRequest;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.MergeReceipt;
import com.example.starter.fermentation.domain.MergeReceiptItem;
import com.example.starter.fermentation.domain.ReceivingTank;
import com.example.starter.fermentation.domain.SourceLiquor;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.repository.BatchRepository;
import com.example.starter.fermentation.repository.ReceivingRepository;
import com.example.starter.fermentation.service.IdempotencyService.ConcurrentReplayException;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 合批接收服务：合成接收罐登记/容量调整、源批实际清酒量登记（冻结）与一次核销管控、
 * 2 至 5 个同工艺曲线版本受控源批完整合入同一接收罐。
 *
 * <p>核销、容量、乐观版本与幂等在同一事务内提交或回滚；失败不改源量、罐占用与酵母代次。
 * 成功后源批进入原已转出终态 TRANSFERRED 并释放原发酵罐，成熟度及采样只读，
 * 不重新开始主酵、不回收酵母。并发两组争同源或同接收余量时按事务提交次序裁决，
 * 失败方整单回滚，不存在半批。
 */
@Service
public class ReceivingService {

    /** 一次合批接收允许引用的最少不同源批数（恰 2 个合法）。 */
    static final int MIN_SOURCES = 2;
    /** 一次合批接收允许引用的最多不同源批数（恰 5 个合法）。 */
    static final int MAX_SOURCES = 5;

    private final ReceivingRepository receivingRepository;
    private final BatchRepository batchRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReceivingService(ReceivingRepository receivingRepository, BatchRepository batchRepository,
                            IdempotencyService idempotencyService, ObjectMapper objectMapper,
                            Clock clock) {
        this.receivingRepository = receivingRepository;
        this.batchRepository = batchRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 登记合成接收罐（正数容量升数），版本从 1 起；编号重复返回 409。 */
    @Transactional
    public StoredResponse registerTank(RegisterTankRequest req) {
        String endpoint = "POST /api/receiving-tanks";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.tankCode(), req.capacityLiters().toPlainString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            long id;
            try {
                id = receivingRepository.insertTank(req.tankCode(), req.capacityLiters(), utcNow());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("接收罐编号已存在: " + req.tankCode());
            }
            return new StoredResponse(201, toJson(toTankResponse(mustFindTankById(id))));
        });
    }

    /**
     * 调整接收罐容量：不得缩到已接收量以下；expectedVersion 失配返回 409，成功版本加 1。
     */
    @Transactional
    public StoredResponse updateTankCapacity(String tankCode, UpdateTankCapacityRequest req) {
        String endpoint = "PUT /api/receiving-tanks/" + tankCode + "/capacity";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.capacityLiters().toPlainString(), req.expectedVersion()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            ReceivingTank tank = mustFindTank(tankCode);
            if (tank.version() != req.expectedVersion()) {
                throw ApiException.conflict(
                        "接收罐版本已变更：期望 " + req.expectedVersion() + "，实际 " + tank.version());
            }
            if (req.capacityLiters().compareTo(tank.receivedLiters()) < 0) {
                throw ApiException.unprocessable("接收罐容量不得缩到已接收量以下（已接收 "
                        + tank.receivedLiters().toPlainString() + " 升，申请容量 "
                        + req.capacityLiters().toPlainString() + " 升）");
            }
            int updated = receivingRepository.updateCapacityIfNotBelowReceived(tank.id(),
                    req.capacityLiters(), req.expectedVersion(), utcNow());
            if (updated == 0) {
                // 并发下版本或已接收量被另一事务改变
                throw ApiException.conflict("接收罐已被并发变更，本次容量调整未生效: " + tankCode);
            }
            return new StoredResponse(200, toJson(toTankResponse(mustFindTankById(tank.id()))));
        });
    }

    /**
     * 给未转出且原成熟度已合格（JUDGED）的源批登记一次实际清酒量。
     * 正数、最多三位小数且不超过其发酵罐容量；一批仅一次，登记后冻结不可修改或重复登记。
     */
    @Transactional
    public StoredResponse registerSourceLiquor(long batchId, RegisterSourceLiquorRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/source-liquor";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.liters().toPlainString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.status() == BatchStatus.TRANSFERRED) {
                throw ApiException.unprocessable("批次已转出，不得再登记源清酒量: " + batchId);
            }
            if (batch.status() != BatchStatus.JUDGED) {
                throw ApiException.unprocessable(
                        "仅原成熟度已合格且未转出的批次可登记实际清酒量（当前状态 "
                                + batch.status() + "）");
            }
            if (receivingRepository.findSourceLiquorByBatch(batchId).isPresent()) {
                throw ApiException.unprocessable("源批实际清酒量已登记并冻结，不得重复登记或修改: "
                        + batchId);
            }
            int tankCapacity = receivingRepository.findFermentationTankCapacityByBatch(batchId)
                    .orElseThrow(() -> ApiException.notFound(
                            "批次发酵罐容量缺失: " + batchId));
            if (req.liters().compareTo(BigDecimal.valueOf(tankCapacity)) > 0) {
                throw ApiException.unprocessable("实际清酒量 " + req.liters().toPlainString()
                        + " 升超过其发酵罐容量 " + tankCapacity + " 升");
            }
            long id;
            try {
                id = receivingRepository.insertSourceLiquor(batchId, req.liters(), utcNow());
            } catch (DuplicateKeyException e) {
                // 并发重复登记由唯一约束兜底：台账冻结
                throw ApiException.unprocessable("源批实际清酒量已登记并冻结，不得重复登记: "
                        + batchId);
            }
            SourceLiquor source = receivingRepository.findSourceLiquorByBatch(batchId).orElseThrow();
            return new StoredResponse(201, toJson(toSourceResponse(source, batch.batchNo())));
        });
    }

    /**
     * 合批接收：一次引用 2 至 5 个不同受控源批与同一接收罐。来源必须同一工艺曲线版本、
     * 仍满足原转出条件（JUDGED 未转出）且已登记冻结清酒量；完整接收各源登记量，不做部分转出。
     * 合计加既有接收量不得超过容量（恰满允许）；任一源已转出或版本失配则全单拒绝。
     * 受理事务为每个源写入唯一核销（与旧单笔转出共享）、置原已转出终态并释放原罐、
     * 原子累加接收量。合批不重新开始主酵、不回收酵母、不改酵母代次。
     */
    @Transactional
    public StoredResponse mergeReceive(MergeReceiveRequest req) {
        String endpoint = "POST /api/merge-receipts";
        List<Long> orderedIds = orderedDistinct(req.batchIds());
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.receivingTankCode(), orderedIds, req.expectedVersion(),
                        req.receivedAt().toString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            ReceivingTank tank = mustFindTank(req.receivingTankCode());
            if (tank.version() != req.expectedVersion()) {
                throw ApiException.conflict(
                        "接收罐版本已变更：期望 " + req.expectedVersion() + "，实际 " + tank.version());
            }
            if (orderedIds.size() < MIN_SOURCES || orderedIds.size() > MAX_SOURCES) {
                throw ApiException.unprocessable(
                        "一次接收必须引用 " + MIN_SOURCES + " 至 " + MAX_SOURCES + " 个不同源批");
            }

            String commonCurveCode = null;
            int commonCurveVersion = -1;
            BigDecimal total = BigDecimal.ZERO;
            List<MergeItemResponse> items = new ArrayList<>();
            for (Long batchId : orderedIds) {
                FermentationBatch batch = mustFindBatch(batchId);
                if (batch.status() == BatchStatus.TRANSFERRED
                        || receivingRepository.writeoffExists(batchId)) {
                    // 任一源已转出（旧单笔转出或已合批）：全单拒绝
                    throw ApiException.unprocessable(
                            "源批 " + batchId + " 已转出，合批整单拒绝");
                }
                if (batch.status() != BatchStatus.JUDGED) {
                    throw ApiException.unprocessable("源批 " + batchId
                            + " 不满足原转出条件（当前状态 " + batch.status() + "），合批整单拒绝");
                }
                SourceLiquor source = receivingRepository.findSourceLiquorByBatch(batchId)
                        .orElseThrow(() -> ApiException.unprocessable(
                                "源批 " + batchId + " 尚未登记冻结的实际清酒量，合批整单拒绝"));
                if (commonCurveCode == null) {
                    commonCurveCode = batch.curveCode();
                    commonCurveVersion = batch.curveVersion();
                } else if (!commonCurveCode.equals(batch.curveCode())
                        || commonCurveVersion != batch.curveVersion()) {
                    // 版本失配：全单拒绝
                    throw ApiException.unprocessable("来源必须使用同一工艺曲线版本：源批 "
                            + batchId + " 为 " + batch.curveCode() + " v" + batch.curveVersion()
                            + "，首源为 " + commonCurveCode + " v" + commonCurveVersion);
                }
                total = total.add(source.liters());
                items.add(new MergeItemResponse(batchId, batch.batchNo(), batch.curveVersion(),
                        source.liters()));
            }

            if (tank.receivedLiters().add(total).compareTo(tank.capacityLiters()) > 0) {
                throw ApiException.unprocessable("合计接收 " + total.toPlainString()
                        + " 升加既有接收量 " + tank.receivedLiters().toPlainString()
                        + " 升超过接收罐容量 " + tank.capacityLiters().toPlainString()
                        + " 升，合批整单拒绝");
            }

            // 受理单头（任一后续写入失败随事务整体回滚，不会留下孤儿单）
            long receiptId = receivingRepository.insertReceipt(tank.id(), total,
                    req.receivedAt(), utcNow());

            // 唯一核销：与旧单笔转出共享 uk_writeoff_batch；并发重复消费由唯一约束裁决，
            // 抛 DuplicateKeyException 即整单回滚，不留下半批。
            for (Long batchId : orderedIds) {
                try {
                    receivingRepository.insertWriteoff(batchId, "MERGE", receiptId, utcNow());
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict(
                            "源批 " + batchId + " 的清酒量已被并发转出/合批核销，本次合批整单拒绝");
                }
            }

            // 原子入账：版本匹配且余量足够（恰满允许）才累加，并发下按事务次序裁决
            int received = receivingRepository.applyReceive(tank.id(), total,
                    req.expectedVersion(), utcNow());
            if (received == 0) {
                throw ApiException.conflict(
                        "接收罐余量不足或版本已被并发合批改变，本次合批整单拒绝: " + tank.tankCode());
            }

            // 全部源置为原已转出终态并释放原发酵罐；不触碰酵母代次，不重启主酵，不回收酵母
            for (Long batchId : orderedIds) {
                batchRepository.completeTransfer(batchId, req.receivedAt());
            }
            for (MergeItemResponse item : items) {
                receivingRepository.insertReceiptItem(receiptId, item.batchId(), item.batchNo(),
                        item.curveVersion(), item.liters());
            }

            ReceivingTank after = mustFindTankById(tank.id());
            MergeReceiptResponse response = new MergeReceiptResponse(receiptId, tank.tankCode(),
                    total, after.receivedLiters(), after.version(), req.receivedAt(), items);
            return new StoredResponse(201, toJson(response));
        });
    }

    /** 查询接收罐（含容量、已接收量与版本，可复算勾稽）。 */
    @Transactional(readOnly = true)
    public ReceivingTankResponse getTank(String tankCode) {
        return toTankResponse(mustFindTank(tankCode));
    }

    /** 查询源批清酒量台账。 */
    @Transactional(readOnly = true)
    public SourceLiquorResponse getSourceLiquor(long batchId) {
        FermentationBatch batch = mustFindBatch(batchId);
        SourceLiquor source = receivingRepository.findSourceLiquorByBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("源批清酒量台账不存在: " + batchId));
        return toSourceResponse(source, batch.batchNo());
    }

    /** 查询某次合批接收受理单及来源明细（可复算各次接收与来源总升数）。 */
    @Transactional(readOnly = true)
    public MergeReceiptResponse getReceipt(long receiptId) {
        MergeReceipt receipt = receivingRepository.findReceiptById(receiptId)
                .orElseThrow(() -> ApiException.notFound("合批受理单不存在: " + receiptId));
        ReceivingTank tank = mustFindTankById(receipt.receivingTankId());
        List<MergeItemResponse> items = receivingRepository.findItemsByReceipt(receiptId).stream()
                .map(i -> new MergeItemResponse(i.batchId(), i.batchNo(), i.curveVersion(),
                        i.liters()))
                .toList();
        return new MergeReceiptResponse(receipt.id(), tank.tankCode(), receipt.totalLiters(),
                tank.receivedLiters(), tank.version(), receipt.receivedAt(), items);
    }

    /** 查询某接收罐的各次合批受理（按受理次序，可逐单复算累计接收升数）。 */
    @Transactional(readOnly = true)
    public List<MergeReceiptResponse> listReceiptsByTank(String tankCode) {
        ReceivingTank tank = mustFindTank(tankCode);
        return receivingRepository.findReceiptsByTank(tank.id()).stream()
                .map(r -> new MergeReceiptResponse(r.id(), tank.tankCode(), r.totalLiters(),
                        tank.receivedLiters(), tank.version(), r.receivedAt(),
                        receivingRepository.findItemsByReceipt(r.id()).stream()
                                .map(i -> new MergeItemResponse(i.batchId(), i.batchNo(),
                                        i.curveVersion(), i.liters()))
                                .toList()))
                .toList();
    }

    private StoredResponse idempotent(String requestId, String endpoint, String fingerprint,
                                      Supplier<StoredResponse> action) {
        try {
            return idempotencyService.execute(requestId, endpoint, fingerprint, action);
        } catch (ConcurrentReplayException e) {
            // 并发同键：回滚本次业务写入，按首次受理结果裁决
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return idempotencyService.resolveConcurrent(e.requestId(), e.fingerprint());
        }
    }

    private ReceivingTank mustFindTank(String tankCode) {
        return receivingRepository.findTankByCode(tankCode)
                .orElseThrow(() -> ApiException.notFound("接收罐不存在: " + tankCode));
    }

    private ReceivingTank mustFindTankById(long id) {
        return receivingRepository.findTankById(id)
                .orElseThrow(() -> ApiException.notFound("接收罐不存在: " + id));
    }

    private FermentationBatch mustFindBatch(long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
    }

    private ReceivingTankResponse toTankResponse(ReceivingTank t) {
        return new ReceivingTankResponse(t.id(), t.tankCode(), t.capacityLiters(),
                t.receivedLiters(), t.version(), t.createdAt(), t.updatedAt());
    }

    private SourceLiquorResponse toSourceResponse(SourceLiquor s, String batchNo) {
        return new SourceLiquorResponse(s.id(), s.batchId(), batchNo, s.liters(), s.frozen(),
                s.version(), s.createdAt());
    }

    /**
     * 业务源批去重并按 ID 升序规范化：重复 ID 视为同一来源，重复列举直接拒绝；
     * 升序保证受理单明细与幂等指纹的结构稳定。
     */
    private List<Long> orderedDistinct(List<Long> batchIds) {
        Set<Long> distinct = new LinkedHashSet<>(batchIds);
        if (distinct.size() != batchIds.size()) {
            throw ApiException.unprocessable("合批来源不得重复引用同一源批");
        }
        return distinct.stream().sorted().toList();
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 结构化业务字段：JSON 数组序列化，字段中的分隔符经 JSON 转义后仍无歧义。
     */
    private String structured(Object... fields) {
        try {
            return objectMapper.writeValueAsString(List.of(fields));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
