package com.example.starter.feed.service;

import com.example.starter.feed.api.ApproveFormulaRequest;
import com.example.starter.feed.api.BatchView;
import com.example.starter.feed.api.CreateBatchRequest;
import com.example.starter.feed.api.FeedingRecordRequest;
import com.example.starter.feed.api.FeedingRecordView;
import com.example.starter.feed.api.FormulaView;
import com.example.starter.feed.api.ProductionRequest;
import com.example.starter.feed.api.ProductionView;
import com.example.starter.feed.api.SlaughterRequest;
import com.example.starter.feed.api.SlaughterView;
import com.example.starter.feed.api.SubmitFormulaRequest;
import com.example.starter.feed.domain.AlertType;
import com.example.starter.feed.domain.BatchMedication;
import com.example.starter.feed.domain.BatchStatus;
import com.example.starter.feed.domain.ComplianceAlert;
import com.example.starter.feed.domain.FarmBatch;
import com.example.starter.feed.domain.FormulaItem;
import com.example.starter.feed.domain.FormulaVersion;
import com.example.starter.feed.domain.FormulaVersionStatus;
import com.example.starter.feed.domain.IdempotentRequest;
import com.example.starter.feed.domain.Ingredient;
import com.example.starter.feed.error.FeedBusinessException;
import com.example.starter.feed.error.IdempotencyConflictException;
import com.example.starter.feed.repo.FeedRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 饲料配方审核与休药期联动服务。
 * <p>幂等语义由“JVM 条带锁 + 单事务 + 台账唯一键”共同保证：同一 requestId 的请求在单 JVM 内
 * 串行执行完整事务（含提交），事务内先查台账判定指纹再执行业务并落账；
 * 同键同参返回首次结果，同键异参抛 {@link IdempotencyConflictException}（409）且事务回滚、状态不变。
 * 本服务数据仅存活于单 JVM 的 H2 内存库，JVM 内锁口径充分；台账唯一键为最终持久防线。
 */
@Service
public class FeedComplianceService {

    private static final String OP_SUBMIT = "SUBMIT";
    private static final String OP_APPROVE = "APPROVE";
    private static final String OP_PRODUCTION = "PRODUCTION";
    private static final String OP_SLAUGHTER = "SLAUGHTER";

    /** 按 requestId 哈希分条的 JVM 锁，覆盖事务开启到提交，保证同键首提并发也只生效一次。 */
    private static final int LOCK_STRIPES = 256;
    private final ReentrantLock[] stripes;

    private final FeedRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public FeedComplianceService(FeedRepository repository, ObjectMapper objectMapper, Clock clock,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.stripes = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    // ---------------- 配方提交 / 批准 ----------------

    /**
     * 提交配方：装配目录 → 合规校验 → 分配新版本号并冻结组成项快照。
     * 校验不通过也生成版本（REJECTED，带拒绝原因），不产生批准资格。
     */
    public FormulaView submit(SubmitFormulaRequest request) {
        validateItemsPresent(request);
        String fingerprint = Fingerprints.of(OP_SUBMIT, request.requestId(), request);
        return withIdempotency(OP_SUBMIT, request.requestId(), fingerprint, FormulaView.class, () -> {
            Map<String, Ingredient> catalog = loadCatalog(
                    request.items().stream().map(SubmitFormulaRequest.Item::ingredientCode).toList());
            ComplianceValidator.Result result = ComplianceValidator.validate(
                    toItems(request.items()), catalog, repository.findAllContraPairs(),
                    request.targetSpecies() == null ? null : request.targetSpecies().trim());
            Instant now = Instant.now(clock);
            int versionNo = repository.allocateVersionNo(request.formulaCode(), now);
            List<FormulaItem> items = toItems(request.items());
            FormulaVersion version = new FormulaVersion(
                    request.formulaCode(), versionNo,
                    result.passed() ? FormulaVersionStatus.SUBMITTED : FormulaVersionStatus.REJECTED,
                    blankToNull(request.targetSpecies()), result.medicated(),
                    fingerprint, result.reasons(), now, null, items);
            repository.insertVersion(version);
            repository.insertItems(request.formulaCode(), versionNo, items);
            return toFormulaView(version, catalog);
        });
    }

    /** 批准合规校验通过的版本；批准后内容冻结，REJECTED/已批准版本不可再批准。 */
    public FormulaView approve(ApproveFormulaRequest request) {
        String fingerprint = Fingerprints.of(OP_APPROVE, request.requestId(), request);
        return withIdempotency(OP_APPROVE, request.requestId(), fingerprint, FormulaView.class, () -> {
            Instant now = Instant.now(clock);
            int updated = repository.approveVersion(request.formulaCode(), request.versionNo(), now);
            if (updated == 0) {
                FormulaVersion existing = repository.findVersion(request.formulaCode(), request.versionNo());
                if (existing == null) {
                    throw new FeedBusinessException(
                            "配方版本不存在：" + request.formulaCode() + " v" + request.versionNo());
                }
                throw new FeedBusinessException("配方版本当前状态为 " + existing.status()
                        + "，仅合规校验通过待批准（SUBMITTED）的版本可批准；修改请提交新版本");
            }
            FormulaVersion approved = repository.findVersion(request.formulaCode(), request.versionNo());
            return toFormulaView(approved, loadCatalog(
                    approved.items().stream().map(FormulaItem::ingredientCode).toList()));
        });
    }

    // ---------------- 投产 / 休药期 ----------------

    /**
     * 已批准含药配方投产：固化投产版本、登记末次饲喂时刻，批次进入休药期跟踪；
     * 同批次多次投产按“最大休药天数 + 最晚末次饲喂”唯一口径合并。
     */
    public ProductionView produce(ProductionRequest request) {
        if (request.lastFeedingTime() == null) {
            throw new FeedBusinessException("投产必须提供末次饲喂含药饲料时刻 lastFeedingTime");
        }
        String fingerprint = Fingerprints.of(OP_PRODUCTION, request.requestId(), request);
        return withIdempotency(OP_PRODUCTION, request.requestId(), fingerprint, ProductionView.class, () -> {
            Instant now = Instant.now(clock);
            FormulaVersion version = resolveApprovedVersion(request.formulaCode(), request.versionNo());
            if (!version.medicated()) {
                throw new FeedBusinessException("配方 " + request.formulaCode() + " v" + version.versionNo()
                        + " 不含药物添加剂，无需休药期跟踪投产");
            }
            FarmBatch batch = lockBatch(request.batchNo());
            if (batch.status() == BatchStatus.SLAUGHTERED) {
                throw new FeedBusinessException("批次 " + batch.batchNo() + " 已出栏（终态），不可再投产含药配方");
            }
            if (!batch.species().equalsIgnoreCase(nullSafeTrim(version.targetSpecies()))) {
                throw new FeedBusinessException("物种错配：配方目标动物种类为 " + version.targetSpecies()
                        + "，批次 " + batch.batchNo() + " 物种为 " + batch.species());
            }
            int maxDays = maxWithdrawalDaysOf(version);
            repository.insertMedication(new BatchMedication(null, batch.batchNo(), version.formulaCode(),
                    version.versionNo(), request.lastFeedingTime(), maxDays, now, false));

            List<BatchMedication> activeRows = repository.findActiveMedications(batch.batchNo());
            WithdrawalCalculator.Window window = WithdrawalCalculator.calculate(activeRows);
            return new ProductionView(batch.batchNo(), version.formulaCode(), version.versionNo(),
                    request.lastFeedingTime(), window.latestFeedingTime(), window.maxWithdrawalDays(),
                    window.earliestSlaughterDate());
        });
    }

    /**
     * 出栏申请：休药期内拒绝并提示最早合法出栏日；已出栏批次的任何重复申请返回幂等成功。
     */
    public SlaughterView slaughter(SlaughterRequest request) {
        String fingerprint = Fingerprints.of(OP_SLAUGHTER, request.requestId(), request);
        return withIdempotency(OP_SLAUGHTER, request.requestId(), fingerprint, SlaughterView.class, () -> {
            Instant applyTime = request.applyTime() == null ? Instant.now(clock) : request.applyTime();
            FarmBatch batch = lockBatch(request.batchNo());

            if (batch.status() == BatchStatus.SLAUGHTERED) {
                return repeatedSlaughterView(batch);
            }

            List<BatchMedication> rows = repository.findActiveMedications(batch.batchNo());
            if (!rows.isEmpty()) {
                WithdrawalCalculator.Window window = WithdrawalCalculator.calculate(rows);
                if (!WithdrawalCalculator.canSlaughter(window, applyTime)) {
                    return new SlaughterView(batch.batchNo(), false, BatchStatus.ACTIVE.name(), false,
                            window.earliestSlaughterDate(), null,
                            "批次处于休药期内，最早合法出栏日（UTC）为 " + window.earliestSlaughterDate()
                                    + "（恰满休药天数当日 24:00 后方可出栏）");
                }
            }

            int updated = repository.markSlaughtered(batch.batchNo(), applyTime);
            if (updated == 0) {
                FarmBatch reloaded = repository.findBatch(batch.batchNo());
                return new SlaughterView(batch.batchNo(), true, BatchStatus.SLAUGHTERED.name(), true,
                        null, reloaded == null ? null : reloaded.slaughteredAt(),
                        "批次已出栏，重复出栏申请按幂等成功处理");
            }
            FarmBatch slaughtered = repository.findBatch(batch.batchNo());
            return new SlaughterView(batch.batchNo(), true, BatchStatus.SLAUGHTERED.name(), false,
                    null, slaughtered.slaughteredAt(), null);
        });
    }

    private SlaughterView repeatedSlaughterView(FarmBatch batch) {
        List<BatchMedication> rows = repository.findActiveMedications(batch.batchNo());
        WithdrawalCalculator.Window window = rows.isEmpty() ? null
                : WithdrawalCalculator.calculate(rows);
        return new SlaughterView(batch.batchNo(), true, BatchStatus.SLAUGHTERED.name(), true,
                window == null ? null : window.earliestSlaughterDate(),
                batch.slaughteredAt(), "批次已出栏，重复出栏申请按幂等成功处理");
    }

    /**
     * 上报含药饲喂记录：批次在栏则并入休药期跟踪；批次已出栏则只登记迟到事件并触发合规告警，
     * 不修改已出栏状态。
     */
    public FeedingRecordView recordFeeding(FeedingRecordRequest request) {
        if (request.feedingTime() == null) {
            throw new FeedBusinessException("饲喂记录必须提供 feedingTime");
        }
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now(clock);
            FormulaVersion version = resolveApprovedVersion(request.formulaCode(), request.versionNo());
            if (!version.medicated()) {
                throw new FeedBusinessException("配方 " + request.formulaCode() + " v" + version.versionNo()
                        + " 不含药物添加剂，不作为含药饲喂记录受理");
            }
            FarmBatch batch = lockBatch(request.batchNo());
            int maxDays = maxWithdrawalDaysOf(version);
            if (batch.status() == BatchStatus.SLAUGHTERED) {
                repository.insertMedication(new BatchMedication(null, batch.batchNo(), version.formulaCode(),
                        version.versionNo(), request.feedingTime(), maxDays, now, true));
                String detail = "批次已于 " + batch.slaughteredAt() + " 出栏，迟到的含药饲喂记录（配方 "
                        + version.formulaCode() + " v" + version.versionNo() + "，饲喂时刻 " + request.feedingTime()
                        + "）不改变出栏状态";
                repository.insertAlert(batch.batchNo(), AlertType.LATE_FEEDING, detail, now);
                return new FeedingRecordView(batch.batchNo(), true, true, now);
            }
            repository.insertMedication(new BatchMedication(null, batch.batchNo(), version.formulaCode(),
                    version.versionNo(), request.feedingTime(), maxDays, now, false));
            return new FeedingRecordView(batch.batchNo(), false, false, now);
        });
    }

    // ---------------- 建档 / 查询（复算入口） ----------------

    /** 建立合成养殖批次；同号重复建档幂等返回当前批次视图，已有数据不变。 */
    public BatchView createBatch(CreateBatchRequest request) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now(clock);
            FarmBatch existing = repository.findBatch(request.batchNo());
            if (existing == null) {
                try {
                    repository.insertBatchIfAbsent(request.batchNo(), request.species().trim(), now);
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    // 并发建档：已有行胜出，不改变既有数据
                }
            }
            return getBatch(request.batchNo());
        });
    }

    /** 查询批次休药期跟踪全貌：义务行轨迹、合并窗口、告警，全部可据此复算。 */
    public BatchView getBatch(String batchNo) {
        FarmBatch batch = repository.findBatch(batchNo);
        if (batch == null) {
            throw new FeedBusinessException("养殖批次不存在：" + batchNo);
        }
        List<BatchMedication> all = repository.findAllMedications(batchNo);
        List<BatchMedication> active = all.stream().filter(m -> !m.late()).toList();
        WithdrawalCalculator.Window window = active.isEmpty() ? null
                : WithdrawalCalculator.calculate(active);
        List<BatchView.Medication> meds = all.stream()
                .map(m -> new BatchView.Medication(m.formulaCode(), m.versionNo(), m.lastFeedingTime(),
                        m.maxWithdrawalDays(), m.late()))
                .toList();
        List<BatchView.Alert> alerts = repository.findAlerts(batchNo).stream()
                .map((ComplianceAlert a) -> new BatchView.Alert(a.alertType().name(), a.detail(), a.createdAt()))
                .toList();
        return new BatchView(batch.batchNo(), batch.species(), batch.status().name(), batch.createdAt(),
                batch.slaughteredAt(),
                window == null ? null : window.latestFeedingTime(),
                window == null ? 0 : window.maxWithdrawalDays(),
                window == null ? null : window.earliestSlaughterDate(), meds, alerts);
    }

    /** 查询配方版本轨迹（按版本号升序）。 */
    public List<FormulaView> listVersions(String formulaCode) {
        List<FormulaView> views = new ArrayList<>();
        for (int v = 1; ; v++) {
            FormulaVersion version = repository.findVersion(formulaCode, v);
            if (version == null) {
                break;
            }
            Map<String, Ingredient> catalog = loadCatalog(
                    version.items().stream().map(FormulaItem::ingredientCode).toList());
            views.add(toFormulaView(version, catalog));
        }
        if (views.isEmpty()) {
            throw new FeedBusinessException("配方不存在或尚无版本：" + formulaCode);
        }
        return views;
    }

    // ---------------- 内部辅助 ----------------

    /**
     * 幂等模板：条带锁覆盖“开事务 → 查台账 → 执行业务 → 落账 → 提交”全过程。
     * 命中且指纹一致回放首次响应；异参抛 409（事务回滚，状态不变）。
     * 业务校验异常直接向外抛且不落账。
     */
    private <T> T withIdempotency(String operation, String requestId, String fingerprint,
                                  Class<T> viewType, IdempotentAction<T> action) {
        ReentrantLock lock = stripes[Math.floorMod(requestId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return transactionTemplate.execute(status -> {
                IdempotentRequest existing = repository.findIdempotent(requestId);
                if (existing != null) {
                    if (!existing.fingerprint().equals(fingerprint)) {
                        throw new IdempotencyConflictException(requestId);
                    }
                    return readJson(existing.responseJson(), viewType);
                }
                T response = action.run();
                IdempotentRequest row = new IdempotentRequest(requestId, operation, fingerprint,
                        writeJson(response), Instant.now(clock));
                if (!repository.insertIdempotentIfAbsent(row)) {
                    // 持久层唯一键兜底：理论同键并发已由条带锁串行化，进入此分支说明跨进程重放，直接回放
                    IdempotentRequest winner = repository.findIdempotent(requestId);
                    if (winner != null && winner.fingerprint().equals(fingerprint)) {
                        return readJson(winner.responseJson(), viewType);
                    }
                    throw new IdempotencyConflictException(requestId);
                }
                return response;
            });
        } finally {
            lock.unlock();
        }
    }

    private interface IdempotentAction<T> {
        T run();
    }

    private FormulaVersion resolveApprovedVersion(String formulaCode, Integer versionNo) {
        FormulaVersion version = versionNo == null
                ? repository.findLatestApproved(formulaCode)
                : repository.findVersion(formulaCode, versionNo);
        if (version == null) {
            throw new FeedBusinessException(versionNo == null
                    ? "配方 " + formulaCode + " 不存在已批准版本"
                    : "配方版本不存在：" + formulaCode + " v" + versionNo);
        }
        if (version.status() != FormulaVersionStatus.APPROVED) {
            throw new FeedBusinessException("配方 " + formulaCode + " v" + version.versionNo()
                    + " 当前状态为 " + version.status() + "，仅已批准冻结版本可投产/饲喂");
        }
        return version;
    }

    private FarmBatch lockBatch(String batchNo) {
        FarmBatch batch = repository.findBatchForUpdate(batchNo);
        if (batch == null) {
            throw new FeedBusinessException("养殖批次不存在：" + batchNo
                    + "（可通过 POST /api/feed/batches 建立合成批次）");
        }
        return batch;
    }

    private int maxWithdrawalDaysOf(FormulaVersion version) {
        Map<String, Ingredient> catalog = loadCatalog(
                version.items().stream().map(FormulaItem::ingredientCode).toList());
        int max = 0;
        for (FormulaItem item : version.items()) {
            Ingredient ingredient = catalog.get(item.ingredientCode());
            if (ingredient != null && ingredient.medicated() && ingredient.withdrawalDays() != null
                    && ingredient.withdrawalDays() > max) {
                max = ingredient.withdrawalDays();
            }
        }
        return max;
    }

    private Map<String, Ingredient> loadCatalog(List<String> codes) {
        Map<String, Ingredient> catalog = new LinkedHashMap<>();
        for (String code : codes) {
            if (!catalog.containsKey(code)) {
                Ingredient ingredient = repository.findIngredient(code);
                if (ingredient != null) {
                    catalog.put(code, ingredient);
                }
            }
        }
        return catalog;
    }

    private List<FormulaItem> toItems(List<SubmitFormulaRequest.Item> items) {
        return items.stream().map(i -> new FormulaItem(i.ingredientCode(), i.ratio())).toList();
    }

    private FormulaView toFormulaView(FormulaVersion version, Map<String, Ingredient> catalog) {
        List<FormulaView.Item> items = version.items().stream()
                .map(i -> {
                    Ingredient ingredient = catalog.get(i.ingredientCode());
                    return new FormulaView.Item(i.ingredientCode(),
                            ingredient == null ? null : ingredient.name(), i.ratio());
                })
                .toList();
        return new FormulaView(version.formulaCode(), version.versionNo(), version.status().name(),
                version.targetSpecies(), version.medicated(), items, version.rejectReasons());
    }

    private void validateItemsPresent(SubmitFormulaRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new FeedBusinessException("配方组成项 items 不能为空");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullSafeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
