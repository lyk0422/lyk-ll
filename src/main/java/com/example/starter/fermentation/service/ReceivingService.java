package com.example.starter.fermentation.service;

import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptRequest;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptResponse;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptSourceResponse;
import com.example.starter.fermentation.api.ReceivingDtos.ReceivingTankResponse;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterReceivingTankRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterSourceVolumeRequest;
import com.example.starter.fermentation.api.ReceivingDtos.SourceVolumeResponse;
import com.example.starter.fermentation.api.ReceivingDtos.UpdateCapacityRequest;
import com.example.starter.fermentation.domain.BatchStatus;
import com.example.starter.fermentation.domain.FermentationBatch;
import com.example.starter.fermentation.domain.MergeReceipt;
import com.example.starter.fermentation.domain.MergeReceiptSource;
import com.example.starter.fermentation.domain.ReceivingTank;
import com.example.starter.fermentation.domain.SourceVolume;
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
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * 合批接收服务：合成接收罐登记与调容、源批实际清酒升数台账（登记一次后冻结）、
 * 2 至 5 个受控源批的合批接收。
 *
 * <p>核心口径：源批须未转出且原成熟度已合格（JUDGED）方可登记实际升数，登记后冻结并启用
 * 一次核销管控；一次接收引用 2 至 5 个不同受控源批与同一接收罐，全部来源须使用同一工艺
 * 曲线版本，完整接收各源登记量，不做部分转出；合计加既有接收量不得超容量（恰满允许）；
 * 受理在同一事务内原子核销所有源为 TRANSFERRED 终态、释放原罐并增加接收量，任一源已转出
 * 或版本失配则全单拒绝回滚；旧单笔转出与合批共享唯一核销，不得重复消费。
 * 台账版本从 1 开始，调容用 expectedVersion、成功加 1、旧版 409；创建时间按 UTC 记录。
 */
@Service
public class ReceivingService {

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

    /**
     * 登记合成接收罐：正数容量升数，版本从 1 开始。编号重复返回 409。
     */
    @Transactional
    public StoredResponse registerTank(RegisterReceivingTankRequest req) {
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
            ReceivingTank tank = mustFindTank(req.tankCode());
            return new StoredResponse(201, toJson(toTankResponse(tank)));
        });
    }

    /**
     * 调整接收罐容量：不得缩到已接收量以下；expectedVersion 旧版返回 409，成功版本加 1。
     */
    @Transactional
    public StoredResponse updateCapacity(String tankCode, UpdateCapacityRequest req) {
        String endpoint = "PUT /api/receiving-tanks/" + tankCode + "/capacity";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.expectedVersion(), req.capacityLiters().toPlainString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            ReceivingTank tank = mustFindTank(tankCode);
            if (req.capacityLiters().compareTo(tank.receivedLiters()) < 0) {
                throw ApiException.unprocessable("接收罐容量不得缩到已接收量 "
                        + tank.receivedLiters().toPlainString() + " 升以下");
            }
            int updated = receivingRepository.tryUpdateCapacity(
                    tankCode, req.capacityLiters(), req.expectedVersion());
            if (updated == 0) {
                throw ApiException.conflict("接收罐 " + tankCode + " 台账版本已变更（当前版本 "
                        + mustFindTank(tankCode).version() + "），本次调容未生效");
            }
            return new StoredResponse(200, toJson(toTankResponse(mustFindTank(tankCode))));
        });
    }

    /**
     * 查询接收罐台账（含已接收量与版本，可与接收单合计勾稽）。
     */
    @Transactional(readOnly = true)
    public ReceivingTankResponse getTank(String tankCode) {
        return toTankResponse(mustFindTank(tankCode));
    }

    /**
     * 登记源批实际清酒升数：源批须未转出且原成熟度已合格（JUDGED），
     * 正数最多三位小数且不超过其发酵罐容量；每批仅登记一次，登记后冻结。
     */
    @Transactional
    public StoredResponse registerSourceVolume(long batchId, RegisterSourceVolumeRequest req) {
        String endpoint = "POST /api/batches/" + batchId + "/sake-volume";
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.volumeLiters().toPlainString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            FermentationBatch batch = mustFindBatch(batchId);
            if (batch.status() != BatchStatus.JUDGED) {
                throw ApiException.unprocessable("仅未转出且原成熟度已合格（JUDGED）的源批可登记实际清酒升数"
                        + "（当前状态 " + batch.status() + "）");
            }
            BigDecimal tankCapacity = receivingRepository.findFermentationTankCapacity(batch.tankId())
                    .orElseThrow(() -> ApiException.notFound("源批发酵罐不存在: " + batch.tankId()));
            if (req.volumeLiters().compareTo(tankCapacity) > 0) {
                throw ApiException.unprocessable("实际清酒升数不得超过源发酵罐容量 "
                        + tankCapacity.toPlainString() + " 升");
            }
            try {
                receivingRepository.insertSourceVolume(batchId, req.volumeLiters(), utcNow());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("批次 " + batchId + " 已登记实际清酒升数，登记后冻结不可重复登记");
            }
            SourceVolume volume = receivingRepository.findVolumeByBatchId(batchId)
                    .orElseThrow(() -> new IllegalStateException("源量台账登记后不存在"));
            return new StoredResponse(201, toJson(toVolumeResponse(volume)));
        });
    }

    /**
     * 查询源批实际清酒升数台账。
     */
    @Transactional(readOnly = true)
    public SourceVolumeResponse getSourceVolume(long batchId) {
        mustFindBatch(batchId);
        return receivingRepository.findVolumeByBatchId(batchId)
                .map(this::toVolumeResponse)
                .orElseThrow(() -> ApiException.notFound("批次 " + batchId + " 尚未登记实际清酒升数"));
    }

    /**
     * 合批接收：2 至 5 个不同受控源批进入同一接收罐。
     * 核销、容量、版本与幂等记录在同一事务内提交；任一校验失败全单回滚，
     * 不改源量、罐占用或酵母代次。
     */
    @Transactional
    public StoredResponse merge(MergeReceiptRequest req) {
        String endpoint = "POST /api/merge-receipts";
        List<Long> sortedIds = req.sourceBatchIds().stream().sorted().toList();
        String fingerprint = idempotencyService.fingerprint(endpoint, req.requestId(),
                structured(req.receivingTankCode(), sortedIds, req.receivedAt().toString()));
        return idempotent(req.requestId(), endpoint, fingerprint, () -> {
            if (new HashSet<>(req.sourceBatchIds()).size() != req.sourceBatchIds().size()) {
                throw ApiException.unprocessable("一次接收的源批必须互不相同");
            }
            // 锁定接收罐行：容量校验与接收落账按事务次序串行
            ReceivingTank tank = receivingRepository.lockTankByCode(req.receivingTankCode())
                    .orElseThrow(() -> ApiException.notFound("接收罐不存在: " + req.receivingTankCode()));

            List<FermentationBatch> sources = new ArrayList<>();
            List<SourceVolume> volumes = new ArrayList<>();
            for (Long sourceId : sortedIds) {
                FermentationBatch batch = mustFindBatch(sourceId);
                if (batch.status() != BatchStatus.JUDGED) {
                    throw ApiException.unprocessable("源批 " + sourceId + " 已转出或不满足原转出条件"
                            + "（当前状态 " + batch.status() + "），全单拒绝");
                }
                SourceVolume volume = receivingRepository.findVolumeByBatchId(sourceId)
                        .orElseThrow(() -> ApiException.unprocessable(
                                "源批 " + sourceId + " 未登记实际清酒升数，非受控源批，全单拒绝"));
                sources.add(batch);
                volumes.add(volume);
            }
            String curveCode = sources.get(0).curveCode();
            int curveVersion = sources.get(0).curveVersion();
            for (FermentationBatch source : sources) {
                if (!source.curveCode().equals(curveCode) || source.curveVersion() != curveVersion) {
                    throw ApiException.unprocessable("来源须使用同一工艺曲线版本：源批 " + source.id()
                            + " 为 " + source.curveCode() + " v" + source.curveVersion()
                            + "，与首源 " + curveCode + " v" + curveVersion + " 失配，全单拒绝");
                }
            }
            BigDecimal total = volumes.stream()
                    .map(SourceVolume::volumeLiters)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (tank.receivedLiters().add(total).compareTo(tank.capacityLiters()) > 0) {
                throw ApiException.unprocessable("合计接收量 " + total.toPlainString()
                        + " 升加既有接收量 " + tank.receivedLiters().toPlainString()
                        + " 升超过接收罐容量 " + tank.capacityLiters().toPlainString() + " 升，全单拒绝");
            }

            // 原子核销：与旧单笔转出共享唯一核销，任一源核销失败全单回滚
            for (FermentationBatch source : sources) {
                int writtenOff = batchRepository.tryCompleteTransfer(source.id(), req.receivedAt());
                if (writtenOff == 0) {
                    throw ApiException.conflict("源批 " + source.id()
                            + " 的核销已被并发消费（旧单笔转出或另一合批），全单拒绝");
                }
            }
            receivingRepository.addReceived(tank.id(), total);
            long receiptId = receivingRepository.insertReceipt(tank.id(), curveCode, curveVersion,
                    total, req.receivedAt(), utcNow());
            for (int i = 0; i < sources.size(); i++) {
                receivingRepository.insertReceiptSource(receiptId, sources.get(i).id(),
                        volumes.get(i).volumeLiters());
            }
            MergeReceipt receipt = receivingRepository.findReceiptsByTankId(tank.id()).stream()
                    .filter(r -> r.id() == receiptId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("接收单落库后不存在"));
            return new StoredResponse(201, toJson(toReceiptResponse(receipt, tank.tankCode())));
        });
    }

    /**
     * 查询接收罐的全部合批接收单（含来源明细，可复算各次接收及来源总升数）。
     */
    @Transactional(readOnly = true)
    public List<MergeReceiptResponse> getReceipts(String tankCode) {
        ReceivingTank tank = mustFindTank(tankCode);
        return receivingRepository.findReceiptsByTankId(tank.id()).stream()
                .map(r -> toReceiptResponse(r, tank.tankCode()))
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

    private FermentationBatch mustFindBatch(long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
    }

    /** 台账创建时间按 UTC 记录。 */
    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private ReceivingTankResponse toTankResponse(ReceivingTank tank) {
        return new ReceivingTankResponse(tank.id(), tank.tankCode(), tank.capacityLiters(),
                tank.receivedLiters(), tank.version(), tank.createdAt());
    }

    private SourceVolumeResponse toVolumeResponse(SourceVolume volume) {
        return new SourceVolumeResponse(volume.id(), volume.batchId(), volume.volumeLiters(),
                volume.version(), volume.createdAt());
    }

    private MergeReceiptResponse toReceiptResponse(MergeReceipt receipt, String tankCode) {
        List<MergeReceiptSourceResponse> sources = new ArrayList<>();
        for (MergeReceiptSource source : receivingRepository.findReceiptSources(receipt.id())) {
            String batchNo = batchRepository.findById(source.batchId())
                    .map(FermentationBatch::batchNo)
                    .orElse("UNKNOWN");
            sources.add(new MergeReceiptSourceResponse(source.batchId(), batchNo,
                    source.volumeLiters()));
        }
        return new MergeReceiptResponse(receipt.id(), tankCode, receipt.curveCode(),
                receipt.curveVersion(), receipt.totalLiters(), receipt.receivedAt(),
                sources, receipt.createdAt());
    }

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
