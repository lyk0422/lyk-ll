package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.InquiryStatus;
import com.example.starter.damage.domain.QualificationStatus;
import com.example.starter.damage.dto.InquiryRequests.BidLineInput;
import com.example.starter.damage.dto.InquiryRequests.CreateSupplierRequest;
import com.example.starter.damage.dto.InquiryRequests.InquiryItemSpec;
import com.example.starter.damage.dto.InquiryRequests.OpenBidsRequest;
import com.example.starter.damage.dto.InquiryRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationGrantRequest;
import com.example.starter.damage.dto.InquiryRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.InquiryRequests.ReviewRequest;
import com.example.starter.damage.dto.InquiryRequests.SubmitBidRequest;
import com.example.starter.damage.dto.InquiryViews.BidLineView;
import com.example.starter.damage.dto.InquiryViews.BidVersionView;
import com.example.starter.damage.dto.InquiryViews.BidView;
import com.example.starter.damage.dto.InquiryViews.InquiryItemView;
import com.example.starter.damage.dto.InquiryViews.InquiryView;
import com.example.starter.damage.dto.InquiryViews.OpeningEntryView;
import com.example.starter.damage.dto.InquiryViews.OpeningView;
import com.example.starter.damage.dto.InquiryViews.QualificationView;
import com.example.starter.damage.dto.InquiryViews.ReviewResultView;
import com.example.starter.damage.dto.InquiryViews.SupplierView;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
import com.example.starter.damage.store.InquiryRepository.BidLineRow;
import com.example.starter.damage.store.InquiryRepository.BidRow;
import com.example.starter.damage.store.InquiryRepository.BidVersionRow;
import com.example.starter.damage.store.InquiryRepository.ConvertedWinRow;
import com.example.starter.damage.store.InquiryRepository.InquiryItemRow;
import com.example.starter.damage.store.InquiryRepository.InquiryRow;
import com.example.starter.damage.store.InquiryRepository.OpeningEntryRow;
import com.example.starter.damage.store.InquiryRepository.OpeningRow;
import com.example.starter.damage.store.SupplierRepository;
import com.example.starter.damage.store.SupplierRepository.QualRow;
import com.example.starter.damage.store.SupplierRepository.SupplierRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 修箱批复前密封询价事务内服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>询价版本固定残损项集合与每项允许工法，并设 UTC 报价截止时刻；now&lt;截止方可投标，恰在截止拒绝。</li>
 *   <li>一供应商一询价仅一个当前报价，修订追加版本并保留历史；整版校验，任一不符整版拒绝。</li>
 *   <li>截止后开标冻结全部报价，按开标时刻供应商资格有效状态排除/入选（不采用提交时旧资格），
 *       有效报价取总价最低，同价按供应商编号升序；零有效报价不开批复。</li>
 *   <li>开标结果首次冻结，重复开标始终返回首次清单，不随后续资格变化重算。</li>
 *   <li>两名不同复核人齐后，中标报价转为原案修理批复，并冻结询价与报价；原全损判定仍适用。</li>
 *   <li>撤销资格追加新版本；与开标经供应商行锁串行保证一致；批复后撤销仅记风险事件，不改历史中标证据。</li>
 * </ul>
 */
@Service
public class InquiryTxService {

    /** 开标清单中资格失效的排除原因 */
    private static final String REASON_REVOKED = "QUALIFICATION_REVOKED";
    private static final String REASON_EXPIRED = "QUALIFICATION_EXPIRED";

    /** 批复后撤销资格的风险事件类型 */
    private static final String EVENT_QUALIFICATION_RISK = "QUALIFICATION_RISK";

    private final SupplierRepository supplierRepository;
    private final InquiryRepository inquiryRepository;
    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final IdempotencyExecutor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InquiryTxService(SupplierRepository supplierRepository,
                            InquiryRepository inquiryRepository,
                            DamageCaseRepository caseRepository,
                            DamageFlowRepository flowRepository,
                            IdempotencyExecutor idempotency,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.supplierRepository = supplierRepository;
        this.inquiryRepository = inquiryRepository;
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    // ============================ 供应商与资格 ============================

    /** 创建合成供应商（含适用工法）；供应商编号唯一，未知工法 400 */
    @Transactional
    public SupplierView createSupplier(CreateSupplierRequest request) {
        for (String method : request.methods()) {
            if (!supplierRepository.methodExists(method)) {
                throw ApiException.badRequest("未知修箱工法代码: " + method);
            }
        }
        if (new LinkedHashSet<>(request.methods()).size() != request.methods().size()) {
            throw ApiException.badRequest("适用工法不得重复");
        }
        Instant now = now();
        long supplierId;
        try {
            supplierId = supplierRepository.insertSupplier(request.supplierNo(), request.name(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("供应商编号已存在: " + request.supplierNo());
        }
        for (String method : request.methods()) {
            supplierRepository.addMethod(supplierId, method);
        }
        SupplierView view = toSupplierView(requireSupplierById(supplierId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 授予/续期有效资格版本；expectedVersion 为当前最新资格版本号，首次授予传 0 */
    @Transactional
    public SupplierView grantQualification(String supplierNo, QualificationGrantRequest request) {
        SupplierRow supplier = lockSupplierByNo(supplierNo);
        Optional<QualRow> latest = supplierRepository.findLatestQualification(supplier.id());
        int expected = requireExpectedQualVersion(latest, request.expectedVersion());
        if (request.validTo() != null && !request.validTo().isAfter(request.validFrom())) {
            throw ApiException.badRequest("资格失效时刻必须晚于生效时刻");
        }
        int newVersion = expected + 1;
        supplierRepository.insertQualification(supplier.id(), newVersion, QualificationStatus.ACTIVE,
                request.validFrom(), request.validTo(), request.requestId(), now());
        SupplierView view = toSupplierView(supplier);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 撤销资格：追加 REVOKED 新版本（validFrom=validTo=撤销时刻）。
     * 若该供应商的中标已转批复，则仅在相应案件记录风险事件，历史开标/报价/资格快照不改写。
     */
    @Transactional
    public SupplierView revokeQualification(String supplierNo, QualificationRevokeRequest request) {
        SupplierRow supplier = lockSupplierByNo(supplierNo);
        Optional<QualRow> latest = supplierRepository.findLatestQualification(supplier.id());
        int expected = requireExpectedQualVersion(latest, request.expectedVersion());
        if (latest.isEmpty()) {
            throw ApiException.unprocessable("供应商尚无有效资格版本，无法撤销");
        }
        if (latest.get().status() == QualificationStatus.REVOKED) {
            throw ApiException.unprocessable("供应商资格已撤销，请勿重复撤销");
        }
        Instant revokedAt = request.revokedAt();
        int newVersion = expected + 1;
        supplierRepository.insertQualification(supplier.id(), newVersion, QualificationStatus.REVOKED,
                revokedAt, revokedAt, request.requestId(), now());

        // 已转批复的中标：仅追加风险事件，冻结证据不回写
        for (ConvertedWinRow win : inquiryRepository.listConvertedWinsForSupplier(supplier.id())) {
            flowRepository.insertEvent(win.caseId(), EVENT_QUALIFICATION_RISK,
                    buildRiskPayload(supplier.supplierNo(), win, newVersion, revokedAt), LocalDateTime.now(clock));
        }

        SupplierView view = toSupplierView(supplier);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    private int requireExpectedQualVersion(Optional<QualRow> latest, int expectedVersion) {
        int current = latest.map(QualRow::version).orElse(0);
        if (expectedVersion != current) {
            throw ApiException.conflict("资格版本已变化，期望版本 " + expectedVersion + " 与当前版本 " + current + " 不一致");
        }
        return current;
    }

    private SupplierRow lockSupplierByNo(String supplierNo) {
        SupplierRow supplier = supplierRepository.findByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        return supplierRepository.findByIdForUpdate(supplier.id())
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
    }

    private SupplierRow requireSupplierById(long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierId));
    }

    // ============================ 发布询价 ============================

    /** 对“待批复”（已估价且判定修理）残损案发布密封询价版本 */
    @Transactional
    public InquiryView publishInquiry(long caseId, PublishInquiryRequest request) {
        CaseRow caseRow = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        if (caseRow.status() != CaseStatus.ESTIMATED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("仅已估价且判定修理的待批复残损案可发布询价，当前状态: "
                    + caseRow.status() + "，判定: " + caseRow.determination());
        }
        EstimateView currentEstimate = flowRepository.findCurrentEstimate(caseId);
        if (currentEstimate == null) {
            throw ApiException.unprocessable("无有效估价版本，无法发布询价");
        }
        if (!request.deadline().isAfter(now())) {
            throw ApiException.unprocessable("报价截止时刻必须晚于当前时刻");
        }

        int maxSeq = inquiryRepository.maxSeq(caseId);
        if (request.expectedVersion() != maxSeq) {
            throw ApiException.conflict("询价版本已变化，期望序号 " + request.expectedVersion()
                    + " 与当前序号数 " + maxSeq + " 不一致");
        }
        Optional<InquiryRow> latestInquiry = inquiryRepository.findLatestInquiryByCase(caseId);
        if (latestInquiry.isPresent() && latestInquiry.get().status() != InquiryStatus.NO_AWARD) {
            throw ApiException.unprocessable("当前询价版本尚未结束（状态 " + latestInquiry.get().status()
                    + "），不能发布新版本");
        }

        // 固定残损项集合：请求必须与案件残损项一一对应
        List<DamageItemView> caseItems = flowRepository.listItems(caseId);
        Map<Long, DamageItemView> caseItemMap = caseItems.stream()
                .collect(Collectors.toMap(DamageItemView::id, v -> v, (a, b) -> a, LinkedHashMap::new));
        if (request.items().size() != caseItemMap.size()) {
            throw ApiException.badRequest("询价残损项数量必须与案件残损项一致: 需 " + caseItemMap.size()
                    + " 项，实供 " + request.items().size() + " 项");
        }
        Set<Long> covered = new LinkedHashSet<>();
        for (InquiryItemSpec spec : request.items()) {
            if (!caseItemMap.containsKey(spec.damageItemId())) {
                throw ApiException.badRequest("询价残损项不属于该案件或不存在: " + spec.damageItemId());
            }
            if (!covered.add(spec.damageItemId())) {
                throw ApiException.badRequest("询价残损项不得重复: " + spec.damageItemId());
            }
            if (new LinkedHashSet<>(spec.methods()).size() != spec.methods().size()) {
                throw ApiException.badRequest("残损项允许工法不得重复: " + spec.damageItemId());
            }
            for (String method : spec.methods()) {
                if (!supplierRepository.methodExists(method)) {
                    throw ApiException.badRequest("未知修箱工法代码: " + method);
                }
            }
        }

        int seq = maxSeq + 1;
        long inquiryId = inquiryRepository.insertInquiry(caseId, seq, request.deadline(),
                request.requestId(), now());
        int lineNo = 1;
        for (InquiryItemSpec spec : request.items()) {
            long inquiryItemId = inquiryRepository.insertInquiryItem(inquiryId, lineNo, spec.damageItemId());
            for (String method : spec.methods()) {
                inquiryRepository.addInquiryItemMethod(inquiryItemId, method);
            }
            lineNo++;
        }

        InquiryView view = toInquiryView(requireInquiry(inquiryId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ============================ 报价 ============================

    /** 提交/修订整版报价；整版校验，任一不符整版拒绝（不保留任何半截版本） */
    @Transactional
    public BidView submitBid(long inquiryId, String supplierNo, SubmitBidRequest request) {
        InquiryRow inquiry = inquiryRepository.findInquiryByIdForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        if (inquiry.status() != InquiryStatus.OPEN) {
            throw ApiException.unprocessable("询价当前状态不允许报价: " + inquiry.status());
        }
        Instant now = now();
        if (!now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("已到或已过报价截止时刻（UTC " + inquiry.deadline() + "），拒绝投标");
        }
        SupplierRow supplier = supplierRepository.findByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        Set<String> supplierMethods = new LinkedHashSet<>(supplierRepository.listMethods(supplier.id()));

        List<InquiryItemRow> inquiryItems = inquiryRepository.listInquiryItems(inquiryId);
        Map<Integer, InquiryItemRow> byLine = inquiryItems.stream()
                .collect(Collectors.toMap(InquiryItemRow::lineNo, r -> r));
        Map<Integer, BidLineInput> inputByLine = new LinkedHashMap<>();
        for (BidLineInput line : request.lines()) {
            if (inputByLine.put(line.lineNo(), line) != null) {
                throw ApiException.unprocessable("报价行号重复: " + line.lineNo());
            }
        }
        if (inputByLine.size() != inquiryItems.size()) {
            throw ApiException.unprocessable("报价必须覆盖全部 " + inquiryItems.size()
                    + " 个残损项，实供 " + inputByLine.size() + " 行，整版拒绝");
        }

        // 先整版校验全部通过后再落库，保证“整版拒绝”
        List<ValidatedLine> validated = new ArrayList<>();
        long total = 0;
        for (InquiryItemRow item : inquiryItems) {
            BidLineInput input = inputByLine.get(item.lineNo());
            if (input == null) {
                throw ApiException.unprocessable("报价缺少残损项行: " + item.lineNo() + "，整版拒绝");
            }
            List<String> allowed = inquiryRepository.listInquiryItemMethods(item.id());
            if (!allowed.contains(input.methodCode())) {
                throw ApiException.unprocessable("第 " + item.lineNo() + " 行工法 " + input.methodCode()
                        + " 不在询价允许工法内，整版拒绝");
            }
            if (!supplierMethods.contains(input.methodCode())) {
                throw ApiException.unprocessable("第 " + item.lineNo() + " 行工法 " + input.methodCode()
                        + " 不在供应商适用工法内，整版拒绝");
            }
            if (input.amountCents() <= 0) {
                throw ApiException.badRequest("报价金额必须为正整数分");
            }
            validated.add(new ValidatedLine(item, input.methodCode(), input.amountCents()));
            total += input.amountCents();
        }

        BidRow bid = inquiryRepository.findBidForUpdate(inquiryId, supplier.id()).orElse(null);
        int currentVersion;
        if (bid == null) {
            if (request.expectedVersion() != 0) {
                throw ApiException.conflict("报价版本不存在，首次报价期望版本必须为 0");
            }
            try {
                long bidId = inquiryRepository.insertBid(inquiryId, supplier.id(), now);
                bid = inquiryRepository.findBidForUpdate(inquiryId, supplier.id())
                        .orElseThrow(() -> new IllegalStateException("报价落库失败"));
            } catch (DuplicateKeyException e) {
                // 并发首次报价由 (inquiry_id, supplier_id) 唯一索引仲裁
                throw ApiException.conflict("该供应商的报价已被并发创建，请基于当前报价版本修订");
            }
            currentVersion = 0;
        } else {
            if (bid.status().equals("FROZEN")) {
                throw ApiException.unprocessable("报价已随开标冻结，不得修订");
            }
            currentVersion = bid.currentVersion();
            if (request.expectedVersion() != currentVersion) {
                throw ApiException.conflict("报价版本已变化，期望版本 " + request.expectedVersion()
                        + " 与当前版本 " + currentVersion + " 不一致");
            }
        }

        int newVersion = currentVersion + 1;
        inquiryRepository.supersedeBidVersions(bid.id());
        long bidVersionId = inquiryRepository.insertBidVersion(bid.id(), newVersion, total,
                request.requestId(), now);
        for (ValidatedLine line : validated) {
            inquiryRepository.insertBidLine(bidVersionId, line.item.id(), line.item.lineNo(),
                    line.methodCode, line.amountCents);
        }
        int updated = inquiryRepository.compareAndSetBidVersion(bid.id(), currentVersion, newVersion, now);
        if (updated == 0) {
            throw ApiException.conflict("报价并发修订冲突，报价已被冻结或更新，请重试");
        }

        BidView view = toBidView(inquiryId, supplier.id());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    private record ValidatedLine(InquiryItemRow item, String methodCode, long amountCents) {
    }

    // ============================ 开标比价 ============================

    /** 截止后开标：冻结全部报价并按开标时刻资格比价；重复开标返回首次冻结清单 */
    @Transactional
    public OpeningView openBids(long inquiryId, OpenBidsRequest request) {
        InquiryRow inquiry = inquiryRepository.findInquiryByIdForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));

        // 业务级幂等：无论是否同一 requestId，重复开标一律返回首次冻结清单，不重算
        Optional<OpeningRow> existing = inquiryRepository.findOpeningByInquiryForUpdate(inquiryId);
        if (existing.isPresent()) {
            OpeningView frozen = toOpeningView(inquiry, existing.get());
            //重复开标即便使用新 requestId 也须写入其幂等完成标记，避免键滞留处理中
            idempotency.completeInTransaction(request.requestId(), frozen);
            return frozen;
        }

        if (inquiry.status() != InquiryStatus.OPEN) {
            throw ApiException.unprocessable("询价当前状态不允许开标: " + inquiry.status());
        }
        Instant openedAt = now();
        if (openedAt.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("未到报价截止时刻，不能开标");
        }
        if (request.expectedVersion() != inquiry.version()) {
            throw ApiException.conflict("询价版本已变化，期望版本 " + request.expectedVersion()
                    + " 与当前版本 " + inquiry.version() + " 不一致");
        }

        // 冻结全部报价（冻结后不得修订）
        inquiryRepository.freezeBids(inquiryId);

        List<BidRow> bids = inquiryRepository.listBids(inquiryId);
        List<Candidate> candidates = new ArrayList<>();
        for (BidRow bid : bids) {
            // 与撤销竞争：先锁供应商行再读资格版本，开标/撤销在此串行
            SupplierRow supplier = supplierRepository.findByIdForUpdate(bid.supplierId())
                    .orElseGet(() -> requireSupplierById(bid.supplierId()));
            QualRow qual = supplierRepository.findLatestQualificationForUpdate(supplier.id()).orElse(null);
            BidVersionRow currentVersion = inquiryRepository.listBidVersions(bid.id()).stream()
                    .filter(v -> v.status().equals("CURRENT"))
                    .findFirst().orElseThrow();

            boolean valid;
            String reason = null;
            if (qual == null) {
                valid = false;
                reason = REASON_EXPIRED;
            } else if (qual.status() == QualificationStatus.REVOKED) {
                valid = false;
                reason = REASON_REVOKED;
            } else if (!covers(qual, openedAt)) {
                valid = false;
                reason = REASON_EXPIRED;
            } else {
                valid = true;
            }
            candidates.add(new Candidate(supplier, qual, currentVersion, valid, reason));
        }

        // 有效报价排序：总价升序，同价按供应商编号升序
        List<Candidate> ranked = candidates.stream()
                .filter(c -> c.valid)
                .sorted(Comparator.comparingLong((Candidate c) -> c.version.totalCents())
                        .thenComparing(c -> c.supplier.supplierNo()))
                .toList();

        boolean awarded = !ranked.isEmpty();
        Candidate winner = awarded ? ranked.get(0) : null;
        String openingStatus = awarded ? "AWARDED" : "NO_VALID";

        long openingId = inquiryRepository.insertOpening(inquiryId, inquiry.version(), openedAt, openingStatus,
                winner == null ? null : winner.supplier.id(),
                winner == null ? null : winner.version.id(),
                winner == null ? null : winner.version.totalCents(),
                request.requestId());
        OpeningRow opening = inquiryRepository.findOpeningByIdForUpdate(openingId).orElseThrow();

        Map<Long, Integer> rankBySupplier = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankBySupplier.put(ranked.get(i).supplier.id(), i + 1);
        }
        for (Candidate c : candidates) {
            inquiryRepository.insertOpeningEntry(openingId, c.supplier.id(), c.version.id(),
                    c.version.totalCents(),
                    c.qual == null ? 0 : c.qual.version(),
                    c.qual == null ? QualificationStatus.REVOKED.name() : c.qual.status().name(),
                    c.qual == null ? openedAt : c.qual.validFrom(),
                    c.qual == null ? openedAt : c.qual.validTo(),
                    c.valid, c.reason, rankBySupplier.get(c.supplier.id()));
        }

        InquiryStatus newInquiryStatus = awarded ? InquiryStatus.OPENED : InquiryStatus.NO_AWARD;
        int updated = inquiryRepository.compareAndSetInquiry(inquiryId, inquiry.version(),
                newInquiryStatus, inquiry.version() + 1);
        if (updated == 0) {
            throw ApiException.conflict("开标并发冲突，询价版本已变化，请重试");
        }

        OpeningView view = toOpeningView(inquiryRepository.findInquiryById(inquiryId).orElseThrow(), opening);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 资格版本是否覆盖指定时刻：ACTIVE 且 validFrom &lt;= t &lt; validTo（validTo 为空表示长期有效） */
    private boolean covers(QualRow qual, Instant t) {
        if (qual.status() != QualificationStatus.ACTIVE) {
            return false;
        }
        if (t.isBefore(qual.validFrom())) {
            return false;
        }
        return qual.validTo() == null || t.isBefore(qual.validTo());
    }

    private record Candidate(SupplierRow supplier, QualRow qual, BidVersionRow version, boolean valid, String reason) {
    }

    // ============================ 双重复核与转批复 ============================

    /** 复核中标报价；两名不同复核人齐后将中标报价转为原案修理批复 */
    @Transactional
    public ReviewResultView review(long inquiryId, ReviewRequest request) {
        InquiryRow inquiry = inquiryRepository.findInquiryByIdForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        OpeningRow opening = inquiryRepository.findOpeningByInquiryForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.unprocessable("询价尚未开标，无法复核"));
        if ("NO_VALID".equals(opening.status())) {
            throw ApiException.unprocessable("开标零有效报价，不开批复");
        }
        if (inquiry.status() == InquiryStatus.CONVERTED) {
            throw ApiException.unprocessable("中标报价已转批复，询价与报价已冻结，不能再复核");
        }
        if (inquiry.status() != InquiryStatus.OPENED) {
            throw ApiException.unprocessable("询价当前状态不允许复核: " + inquiry.status());
        }

        List<String> reviewers = inquiryRepository.listReviews(opening.id()).stream()
                .map(r -> r.reviewer()).distinct().toList();
        if (reviewers.contains(request.reviewer())) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }
        try {
            inquiryRepository.insertReview(inquiryId, opening.id(), request.reviewer(),
                    request.requestId(), now());
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }

        List<String> updatedReviewers = inquiryRepository.listReviews(opening.id()).stream()
                .map(r -> r.reviewer()).distinct().toList();

        LocalDate approvalDate = null;
        Long approvalId = null;
        boolean converted = false;
        if (updatedReviewers.size() >= 2) {
            // 两名不同复核人齐：将中标报价转为原案修理批复
            long caseId = inquiry.caseId();
            CaseRow caseRow = caseRepository.findByIdForUpdate(caseId)
                    .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
            if (caseRow.status() != CaseStatus.ESTIMATED || caseRow.determination() != Determination.REPAIR) {
                throw ApiException.unprocessable("原案已不满足修理批复条件（状态 " + caseRow.status()
                        + "，判定 " + caseRow.determination() + "），全损判定仍适用，转批复拒绝");
            }
            EstimateView estimate = flowRepository.findCurrentEstimate(caseId);
            if (estimate == null) {
                throw ApiException.unprocessable("无有效估价版本，无法转批复");
            }
            approvalDate = LocalDate.now(clock);
            approvalId = flowRepository.insertApproval(caseId, estimate.id(), approvalDate,
                    "INQUIRY", opening.id(), java.time.LocalDateTime.now(clock));
            caseRepository.updateState(caseId, CaseStatus.APPROVED, Determination.REPAIR,
                    java.time.LocalDateTime.now(clock));
            int updated = inquiryRepository.compareAndSetInquiry(inquiryId, inquiry.version(),
                    InquiryStatus.CONVERTED, inquiry.version() + 1);
            if (updated == 0) {
                throw ApiException.conflict("转批复并发冲突，询价版本已变化，请重试");
            }
            converted = true;
        }

        InquiryRow refreshed = inquiryRepository.findInquiryById(inquiryId).orElseThrow();
        OpeningRow refreshedOpening = inquiryRepository.findOpeningByInquiry(inquiryId).orElseThrow();
        OpeningView openingView = toOpeningView(refreshed, refreshedOpening, updatedReviewers, converted, approvalId);
        ReviewResultView view = new ReviewResultView(openingView, approvalDate);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public SupplierView getSupplier(String supplierNo) {
        SupplierRow supplier = supplierRepository.findByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        return toSupplierView(supplier);
    }

    @Transactional(readOnly = true)
    public InquiryView getInquiry(long inquiryId) {
        return toInquiryView(requireInquiry(inquiryId));
    }

    @Transactional(readOnly = true)
    public BidView getBid(long inquiryId, String supplierNo) {
        InquiryRow inquiry = inquiryRepository.findInquiryById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        SupplierRow supplier = supplierRepository.findByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        BidRow bid = inquiryRepository.findBid(inquiryId, supplier.id())
                .orElseThrow(() -> ApiException.notFound("该供应商尚未对本询价报价"));
        return toBidView(inquiry.id(), supplier.id());
    }

    @Transactional(readOnly = true)
    public OpeningView getOpening(long inquiryId) {
        InquiryRow inquiry = inquiryRepository.findInquiryById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        OpeningRow opening = inquiryRepository.findOpeningByInquiry(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价尚未开标，无开标清单"));
        return toOpeningView(inquiry, opening);
    }

    // ============================ 视图装配 ============================

    private InquiryRow requireInquiry(long inquiryId) {
        return inquiryRepository.findInquiryById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
    }

    private SupplierView toSupplierView(SupplierRow supplier) {
        List<String> methods = supplierRepository.listMethods(supplier.id());
        Optional<QualRow> latest = supplierRepository.findLatestQualification(supplier.id());
        QualificationView qualView = latest.map(q -> new QualificationView(
                q.id(), q.version(), q.status(), q.validFrom(), q.validTo())).orElse(null);
        return new SupplierView(supplier.id(), supplier.supplierNo(), supplier.name(),
                methods, qualView, latest.map(QualRow::version).orElse(0));
    }

    private InquiryView toInquiryView(InquiryRow inquiry) {
        Map<Long, DamageItemView> caseItems = flowRepository.listItems(inquiry.caseId()).stream()
                .collect(Collectors.toMap(DamageItemView::id, v -> v));
        List<InquiryItemView> itemViews = new ArrayList<>();
        for (InquiryItemRow row : inquiryRepository.listInquiryItems(inquiry.id())) {
            DamageItemView damage = caseItems.get(row.damageItemId());
            itemViews.add(new InquiryItemView(row.lineNo(), row.damageItemId(),
                    damage == null ? null : damage.iiclCode(),
                    damage == null ? null : damage.component(),
                    inquiryRepository.listInquiryItemMethods(row.id())));
        }
        return new InquiryView(inquiry.id(), inquiry.caseId(), inquiry.seq(), inquiry.version(),
                inquiry.deadline(), inquiry.status(), itemViews);
    }

    private BidView toBidView(long inquiryId, long supplierId) {
        BidRow bid = inquiryRepository.findBid(inquiryId, supplierId).orElseThrow();
        List<BidVersionView> history = new ArrayList<>();
        for (BidVersionRow v : inquiryRepository.listBidVersions(bid.id())) {
            List<BidLineView> lines = inquiryRepository.listBidLines(v.id()).stream()
                    .map(l -> new BidLineView(l.lineNo(), l.methodCode(), l.amountCents()))
                    .toList();
            history.add(new BidVersionView(v.id(), v.version(), v.totalCents(), v.status(), lines));
        }
        return new BidView(bid.id(), inquiryId, supplierId, bid.currentVersion(), bid.status(), history);
    }

    private OpeningView toOpeningView(InquiryRow inquiry, OpeningRow opening) {
        List<String> reviewers = inquiryRepository.listReviews(opening.id()).stream()
                .map(r -> r.reviewer()).toList();
        boolean converted = inquiry.status() == InquiryStatus.CONVERTED;
        ApprovalView approval = flowRepository.findApprovalByOpeningId(opening.id());
        return toOpeningView(inquiry, opening, reviewers, converted, approval == null ? null : approval.id());
    }

    private OpeningView toOpeningView(InquiryRow inquiry, OpeningRow opening, List<String> reviewers,
                                      boolean converted, Long approvalId) {
        List<OpeningEntryView> entries = new ArrayList<>();
        for (OpeningEntryRow e : inquiryRepository.listOpeningEntries(opening.id())) {
            SupplierRow supplier = supplierRepository.findById(e.supplierId()).orElse(null);
            int bidVersionNo = inquiryRepository.findBidVersionById(e.bidVersionId())
                    .map(BidVersionRow::version).orElse(0);
            entries.add(new OpeningEntryView(
                    e.supplierId(),
                    supplier == null ? null : supplier.supplierNo(),
                    e.bidVersionId(),
                    bidVersionNo,
                    e.totalCents(),
                    e.qualificationVersion(),
                    QualificationStatus.valueOf(e.qualificationStatus()),
                    e.qualValidFrom(),
                    e.qualValidTo(),
                    e.valid(),
                    e.excludeReason(),
                    e.rankNo()));
        }
        String winnerNo = null;
        if (opening.winnerSupplierId() != null) {
            winnerNo = supplierRepository.findById(opening.winnerSupplierId())
                    .map(SupplierRow::supplierNo).orElse(null);
        }
        return new OpeningView(opening.id(), inquiry.id(), opening.inquiryVersion(), opening.openedAt(),
                opening.status(), opening.winnerSupplierId(), winnerNo,
                opening.winnerBidVersionId(), opening.winnerTotalCents(),
                reviewers, converted, approvalId, entries);
    }

    private String buildRiskPayload(String supplierNo, ConvertedWinRow win, int qualificationVersion,
                                    Instant revokedAt) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "supplierNo", supplierNo,
                    "inquiryId", win.inquiryId(),
                    "openingId", win.openingId(),
                    "qualificationVersion", qualificationVersion,
                    "revokedAt", revokedAt.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("风险事件负载序列化失败", e);
        }
    }
}
