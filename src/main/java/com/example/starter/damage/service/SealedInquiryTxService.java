package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.BidStatus;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.InquiryStatus;
import com.example.starter.damage.domain.QualificationStatus;
import com.example.starter.damage.domain.SupplierStatus;
import com.example.starter.damage.dto.Requests.AwardConfirmRequest;
import com.example.starter.damage.dto.Requests.BidItemInput;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.BidSubmitRequest;
import com.example.starter.damage.dto.Requests.InquiryItemSpec;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QualificationRenewRequest;
import com.example.starter.damage.dto.Requests.QualificationRevokeRequest;
import com.example.starter.damage.dto.Requests.SupplierCreateRequest;
import com.example.starter.damage.dto.Views.AwardView;
import com.example.starter.damage.dto.Views.BidItemView;
import com.example.starter.damage.dto.Views.BidView;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.OpeningLineView;
import com.example.starter.damage.dto.Views.OpeningView;
import com.example.starter.damage.dto.Views.QualificationView;
import com.example.starter.damage.dto.Views.SupplierView;
import com.example.starter.damage.store.BidRepository;
import com.example.starter.damage.store.BidRepository.AwardRow;
import com.example.starter.damage.store.BidRepository.BidItemRow;
import com.example.starter.damage.store.BidRepository.BidRow;
import com.example.starter.damage.store.BidRepository.OpeningLineRow;
import com.example.starter.damage.store.BidRepository.OpeningRow;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
import com.example.starter.damage.store.InquiryRepository.InquiryRow;
import com.example.starter.damage.store.InquiryRepository.ItemRow;
import com.example.starter.damage.store.SupplierRepository;
import com.example.starter.damage.store.SupplierRepository.QualRow;
import com.example.starter.damage.store.SupplierRepository.SupplierRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修箱批复前密封询价与比价事务服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>待批复（ESTIMATED 且判定 REPAIR）残损案可发布询价版本，固定残损项集合、每项允许工法与 UTC 截止时刻；</li>
 *   <li>now 严格小于截止时刻方可投标，恰在截止时刻拒绝；整版覆盖全部项、金额正整数分、工法有资格，任一不符整版拒绝；</li>
 *   <li>一供应商一询价版本仅一个当前报价，修订保留全部历史；</li>
 *   <li>截止后开标冻结全部当前报价，资格按开标时刻重新判断（不采用投标时旧资格），总价最低中标，同价按供应商编号升序；</li>
 *   <li>重复开标返回首次清单，不因后续资格变化重算；零有效报价不得转批复；</li>
 *   <li>双人不同复核后中标报价才转为原案修理批复，并冻结询价、报价与资格版本；批复后撤销资格只形成风险事件。</li>
 * </ul>
 */
@Service
public class SealedInquiryTxService {

    /** 开标排除原因：资格有效期已过（含恰在失效时刻） */
    static final String REASON_EXPIRED = "SUPPLIER_QUALIFICATION_EXPIRED";
    /** 开标排除原因：资格已被撤销 */
    static final String REASON_REVOKED = "SUPPLIER_QUALIFICATION_REVOKED";

    private final SupplierRepository supplierRepository;
    private final InquiryRepository inquiryRepository;
    private final BidRepository bidRepository;
    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public SealedInquiryTxService(SupplierRepository supplierRepository,
                                  InquiryRepository inquiryRepository,
                                  BidRepository bidRepository,
                                  DamageCaseRepository caseRepository,
                                  DamageFlowRepository flowRepository,
                                  IdempotencyExecutor idempotency,
                                  Clock clock) {
        this.supplierRepository = supplierRepository;
        this.inquiryRepository = inquiryRepository;
        this.bidRepository = bidRepository;
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ==================== 合成供应商与资格 ====================

    /** 登记合成供应商：唯一编号、适用工法集合与首个资格版本（版本从 1 开始） */
    @Transactional
    public SupplierView createSupplier(SupplierCreateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        validateQualWindow(request.validFrom(), request.validTo());
        long supplierId;
        try {
            supplierId = supplierRepository.insertSupplier(request.supplierCode(), request.name(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("供应商编号已存在: " + request.supplierCode());
        }
        supplierRepository.insertMethods(supplierId, request.methods().stream().distinct().sorted().toList());
        supplierRepository.insertQualification(supplierId, 1, request.validFrom(), request.validTo(), now);
        SupplierView view = loadSupplierView(request.supplierCode());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 资格续期：expectedVersion 为供应商当前版本，成功后供应商版本与新资格版本号同时加 1。
     * 旧资格版本原样保留。
     */
    @Transactional
    public SupplierView renewQualification(String supplierCode, QualificationRenewRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        validateQualWindow(request.validFrom(), request.validTo());
        SupplierRow supplier = lockSupplier(supplierCode);
        if (supplier.version() != request.expectedVersion()) {
            throw ApiException.conflict("供应商版本已变化，期望 " + request.expectedVersion()
                    + "，实际 " + supplier.version());
        }
        if (supplier.status() == SupplierStatus.REVOKED) {
            throw ApiException.unprocessable("供应商资格已撤销，不可续期");
        }
        int newVersion = supplier.version() + 1;
        supplierRepository.insertQualification(supplier.id(), newVersion,
                request.validFrom(), request.validTo(), now);
        bumpVersion(supplier, now);
        SupplierView view = loadSupplierView(supplierCode);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 撤销供应商当前资格：expectedVersion 为供应商当前版本，成功后版本加 1。
     * 若该供应商已中标转批复，则额外在对应案件只追加 QUALIFICATION_RISK 风险事件，
     * 中标凭证、冻结报价与批复均不改变。
     */
    @Transactional
    public SupplierView revokeQualification(String supplierCode, QualificationRevokeRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        SupplierRow supplier = lockSupplier(supplierCode);
        if (supplier.version() != request.expectedVersion()) {
            throw ApiException.conflict("供应商版本已变化，期望 " + request.expectedVersion()
                    + "，实际 " + supplier.version());
        }
        QualRow latest = supplierRepository.latestQualification(supplier.id())
                .orElseThrow(() -> ApiException.unprocessable("供应商无资格版本，无法撤销"));
        if (latest.status() == QualificationStatus.REVOKED) {
            throw ApiException.unprocessable("供应商当前资格已撤销，请勿重复撤销");
        }
        supplierRepository.revokeQualification(latest.id(), now, request.reason());
        supplierRepository.markRevoked(supplier.id(), now);
        bumpVersion(supplier, now);

        // 批复后撤销：只形成风险事件，不改历史中标证据（repair_award 快照与 repair_approval 保持不变）
        for (AwardRow award : bidRepository.findAwardsBySupplier(supplier.id())) {
            flowRepository.insertEvent(award.caseId(), "QUALIFICATION_RISK",
                    "中标供应商 " + supplierCode + " 资格已于开标/批复后撤销: " + request.reason(), now);
        }

        SupplierView view = loadSupplierView(supplierCode);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ==================== 询价版本 ====================

    /**
     * 发布询价版本：残损项集合必须与案件残损项一一对应，每项允许工法非空，截止时刻为未来 UTC 时刻。
     * expectedVersion 为当前已有询价版本数（首版传 0）；成功后新版本号 = expectedVersion + 1（从 1 开始）。
     * 存在未截止 OPEN 版本时发布新版本会取代旧版本；已开标/已冻结版本不可再发布。
     */
    @Transactional
    public InquiryView publishInquiry(long caseId, InquiryPublishRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        CaseRow caseRow = lockCase(caseId);
        if (caseRow.status() != CaseStatus.ESTIMATED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("仅判定为修理且待批复的残损案可发布询价，当前状态: "
                    + caseRow.status() + "，判定: " + caseRow.determination());
        }
        if (!request.deadline().isAfter(now)) {
            throw ApiException.unprocessable("报价截止时刻必须晚于当前时刻");
        }
        int currentMax = inquiryRepository.maxVersion(caseId);
        if (request.expectedVersion() != currentMax) {
            throw ApiException.conflict("询价版本已变化，期望已有版本数 " + request.expectedVersion()
                    + "，实际 " + currentMax);
        }
        InquiryRow open = inquiryRepository.findOpenByCase(caseId).orElse(null);
        if (currentMax > 0 && open == null) {
            throw ApiException.unprocessable("已有询价版本开标或冻结，不可再发布新版本");
        }

        List<Long> caseItemIds = flowRepository.listItems(caseId).stream()
                .map(item -> item.id()).sorted().toList();
        if (caseItemIds.isEmpty()) {
            throw ApiException.unprocessable("残损案没有残损项，无法发布询价");
        }
        List<InquiryItemSpec> specs = request.items();
        Set<Long> specIds = new HashSet<>();
        for (InquiryItemSpec spec : specs) {
            if (!specIds.add(spec.damageItemId())) {
                throw ApiException.unprocessable("询价残损项重复: " + spec.damageItemId());
            }
        }
        if (!specIds.equals(new HashSet<>(caseItemIds))) {
            throw ApiException.unprocessable("询价残损项集合必须与案件残损项一一对应");
        }
        for (InquiryItemSpec spec : specs) {
            if (spec.allowedMethods().stream().anyMatch(String::isBlank)) {
                throw ApiException.badRequest("允许工法不可为空白");
            }
        }

        if (open != null) {
            inquiryRepository.updateStatus(open.id(), InquiryStatus.SUPERSEDED);
            bidRepository.supersedeAllCurrent(open.id());
        }
        int newVersion = currentMax + 1;
        long inquiryId = inquiryRepository.insertInquiry(caseId, newVersion, request.deadline(),
                request.requestId(), now);

        // 按案件残损项登记顺序固定询价项与允许工法
        Map<Long, Integer> seqByItem = new HashMap<>();
        int seq = 1;
        for (Long itemId : caseItemIds) {
            seqByItem.put(itemId, seq++);
        }
        var caseItems = flowRepository.listItems(caseId);
        Map<Long, InquiryItemSpec> specByItem = new LinkedHashMap<>();
        for (InquiryItemSpec s : specs) {
            specByItem.put(s.damageItemId(), s);
        }
        for (var damageItem : caseItems) {
            InquiryItemSpec spec = specByItem.get(damageItem.id());
            long inquiryItemId = inquiryRepository.insertItem(inquiryId, seqByItem.get(damageItem.id()),
                    damageItem.id(), damageItem.iiclCode(), damageItem.component());
            spec.allowedMethods().stream().distinct().sorted()
                    .forEach(method -> inquiryRepository.insertItemMethod(inquiryItemId, method));
        }

        InquiryView view = loadInquiryView(inquiryId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ==================== 报价 ====================

    /**
     * 提交或修订报价（整版替换）：expectedVersion 为当前报价修订号，首次投标传 0，成功后加 1。
     * now 必须严格早于截止时刻；整版覆盖全部询价项、金额为正整数分、工法在允许集合且供应商适用，
     * 且供应商在 now 时刻持有有效资格，任一不符整版拒绝（不写入任何报价）。
     */
    @Transactional
    public BidView submitBid(long inquiryId, BidSubmitRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(inquiryId);
        if (inquiry.status() != InquiryStatus.OPEN) {
            throw ApiException.unprocessable("询价版本当前状态不接受报价: " + inquiry.status());
        }
        if (!now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("已到报价截止时刻，不再接受报价（含恰在截止时刻）");
        }
        SupplierRow supplier = lockSupplier(request.supplierCode());
        if (supplier.status() != SupplierStatus.ACTIVE) {
            throw ApiException.unprocessable("供应商资格已撤销，不可投标");
        }
        // 投标时刻必须持有有效资格；开标时还会按开标时刻重新判断，不沿用此处旧资格
        supplierRepository.activeQualificationAt(supplier.id(), now)
                .orElseThrow(() -> ApiException.unprocessable("供应商在报价时刻无有效资格，不可投标"));

        BidRow current = bidRepository.findCurrentBid(inquiryId, supplier.id()).orElse(null);
        int currentRevision = current == null ? 0 : current.revision();
        if (request.expectedVersion() != currentRevision) {
            throw ApiException.conflict("报价修订号已变化，期望 " + request.expectedVersion()
                    + "，实际 " + currentRevision);
        }

        List<ItemRow> inquiryItems = inquiryRepository.listItems(inquiryId);
        Map<Long, BidItemInput> inputByItem = new LinkedHashMap<>();
        for (BidItemInput input : request.items()) {
            if (inputByItem.put(input.inquiryItemId(), input) != null) {
                throw ApiException.unprocessable("报价对同一询价项重复出价: " + input.inquiryItemId());
            }
        }
        if (inputByItem.size() != inquiryItems.size()
                || !inputByItem.keySet().equals(new HashSet<>(inquiryItems.stream().map(ItemRow::id).toList()))) {
            throw ApiException.unprocessable("报价必须整版覆盖全部询价项，不得缺项或多项");
        }
        Set<String> supplierMethods = new HashSet<>(supplierRepository.listMethods(supplier.id()));
        long total = 0;
        List<ValidatedBidItem> validated = new ArrayList<>();
        for (ItemRow inquiryItem : inquiryItems) {
            BidItemInput input = inputByItem.get(inquiryItem.id());
            if (!inquiryRepository.itemAllowsMethod(inquiryItem.id(), input.methodCode())) {
                throw ApiException.unprocessable("工法不在该项允许集合内，整版拒绝: 项序号 "
                        + inquiryItem.seqNo() + "，工法 " + input.methodCode());
            }
            if (!supplierMethods.contains(input.methodCode())) {
                throw ApiException.unprocessable("供应商不具备该工法资格，整版拒绝: 项序号 "
                        + inquiryItem.seqNo() + "，工法 " + input.methodCode());
            }
            if (input.amountCents() <= 0) {
                throw ApiException.unprocessable("报价金额必须为正整数分，整版拒绝: 项序号 " + inquiryItem.seqNo());
            }
            total = Math.addExact(total, input.amountCents());
            validated.add(new ValidatedBidItem(inquiryItem, input));
        }

        if (current != null) {
            bidRepository.supersedeCurrent(inquiryId, supplier.id());
        }
        int newRevision = currentRevision + 1;
        long bidId = bidRepository.insertBid(inquiryId, supplier.id(), newRevision, total,
                BidStatus.CURRENT, request.requestId(), now);
        for (ValidatedBidItem v : validated) {
            bidRepository.insertBidItem(bidId, v.item().id(), v.input().methodCode(), v.input().amountCents());
        }

        BidView view = loadBidView(bidRepository.listBids(inquiryId).stream()
                .filter(b -> b.id() == bidId).findFirst().orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ==================== 开标 ====================

    /**
     * 开标：截止时刻后才可执行；冻结全部当前报价，按开标时刻（now）重新判断各供应商资格，
     * 总价最低者中标，同价按供应商编号升序。已开标则直接返回首次清单，不重算。
     */
    @Transactional
    public OpeningView openBids(long inquiryId, BidOpenRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(inquiryId);
        if (now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("未到报价截止时刻，不得开标");
        }

        // 重复开标（含不同 requestId）一律返回首次冻结清单
        OpeningRow existing = bidRepository.findOpening(inquiryId).orElse(null);
        if (existing != null) {
            OpeningView view = loadOpeningView(existing);
            idempotency.completeInTransaction(request.requestId(), view);
            return view;
        }
        if (inquiry.status() != InquiryStatus.OPEN) {
            throw ApiException.unprocessable("询价版本状态异常，无法开标: " + inquiry.status());
        }

        // 冻结每个供应商唯一的当前报价，资格按开标时刻判断
        List<BidRow> currentBids = bidRepository.listBids(inquiryId).stream()
                .filter(b -> b.status() == BidStatus.CURRENT)
                .toList();
        record Frozen(BidRow bid, SupplierRow supplier, boolean qualified,
                      Integer qualVersion, String reason) {
        }
        List<Frozen> frozen = new ArrayList<>();
        for (BidRow bid : currentBids) {
            // 对供应商行加锁，确保与并发撤销资格串行，资格判断口径与开标时刻严格一致
            SupplierRow locked = supplierRepository.findByIdForUpdate(bid.supplierId())
                    .orElseThrow(() -> ApiException.unprocessable("报价供应商不存在"));
            QualRow qualAtOpening = supplierRepository.activeQualificationAt(locked.id(), now).orElse(null);
            boolean qualified = qualAtOpening != null;
            Integer qualVersion = qualified ? qualAtOpening.version() : null;
            String reason = null;
            if (!qualified) {
                reason = exclusionReason(locked.id(), now);
                bidRepository.freezeCurrentBids(inquiryId, locked.id(),
                        BidStatus.FROZEN_EXCLUDED, reason);
            } else {
                bidRepository.freezeCurrentBids(inquiryId, locked.id(), BidStatus.FROZEN_VALID, null);
            }
            BidRow frozenBid = bidRepository.findBidByIdForUpdate(bid.id()).orElseThrow();
            frozen.add(new Frozen(frozenBid, locked, qualified, qualVersion, reason));
        }

        // 有效报价按总价升序、供应商编号升序排名
        List<Frozen> ranked = frozen.stream()
                .filter(Frozen::qualified)
                .sorted(Comparator.comparingLong((Frozen f) -> f.bid().totalAmountCents())
                        .thenComparing(f -> f.supplier().code()))
                .toList();
        Frozen winner = ranked.isEmpty() ? null : ranked.get(0);

        long openingId = bidRepository.insertOpening(inquiryId, now,
                winner == null ? null : winner.bid().id(),
                winner == null ? null : winner.supplier().code(),
                request.requestId(), now);
        Map<Long, Integer> rankByBid = new HashMap<>();
        int rank = 1;
        for (Frozen f : ranked) {
            rankByBid.put(f.bid().id(), rank++);
        }
        for (Frozen f : frozen) {
            bidRepository.insertOpeningLine(openingId, f.bid().id(), f.supplier().id(), f.supplier().code(),
                    f.bid().revision(), f.bid().totalAmountCents(), f.qualified(), f.qualVersion(),
                    f.reason(), rankByBid.get(f.bid().id()));
        }
        inquiryRepository.updateStatus(inquiryId, InquiryStatus.OPENED);

        OpeningView view = loadOpeningView(bidRepository.findOpening(inquiryId).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ==================== 双人不同复核转批复 ====================

    /**
     * 双人不同复核：expectedVersion 为中标报价修订号；两位复核人非空且不同。
     * 成功后将中标报价转为原案修理批复（沿用既有 repair_approval 与后续放行链），
     * 同时冻结询价、报价与中标供应商开标时有效的资格版本。原全损判定仍适用；零有效报价不得批复。
     */
    @Transactional
    public AwardView confirmAward(long inquiryId, AwardConfirmRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(inquiryId);
        long caseId = inquiry.caseId();
        CaseRow caseRow = lockCase(caseId);
        if (inquiry.status() != InquiryStatus.OPENED) {
            throw ApiException.unprocessable("仅已开标询价可双人复核转批复，当前状态: " + inquiry.status());
        }
        OpeningRow opening = bidRepository.findOpeningForUpdate(inquiryId).orElseThrow();
        if (opening.winnerBidId() == null) {
            throw ApiException.unprocessable("开标无有效报价，不得转修理批复");
        }
        if (caseRow.status() != CaseStatus.ESTIMATED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("原案全损判定仍适用或状态已变化，不得转修理批复: "
                    + caseRow.status() + "，判定: " + caseRow.determination());
        }
        if (request.reviewer1().equals(request.reviewer2())) {
            throw ApiException.unprocessable("双人复核必须由两位不同复核人完成");
        }
        BidRow winnerBid = bidRepository.findBidByIdForUpdate(opening.winnerBidId()).orElseThrow();
        if (winnerBid.revision() != request.expectedVersion()) {
            throw ApiException.conflict("中标报价修订号已变化，期望 " + request.expectedVersion()
                    + "，实际 " + winnerBid.revision());
        }
        if (bidRepository.findAwardByInquiry(inquiryId).isPresent()) {
            throw ApiException.unprocessable("该询价已完成复核转批复，不可重复操作");
        }

        // 取开标时冻结在清单行上的资格版本号，精确冻结该版本（即使开标后续期产生新版本也不影响）
        SupplierRow supplier = supplierRepository.findById(winnerBid.supplierId())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商不存在"));
        OpeningLineRow winnerLine = bidRepository.findOpeningLineByBid(opening.winnerBidId())
                .orElseThrow(() -> ApiException.unprocessable("中标报价缺少开标清单记录，无法冻结资格版本"));
        if (winnerLine.qualificationVersion() == null) {
            throw ApiException.unprocessable("中标报价开标时无有效资格版本，不得转批复");
        }
        QualRow frozenQual = supplierRepository
                .findQualification(supplier.id(), winnerLine.qualificationVersion())
                .orElseThrow(() -> ApiException.unprocessable("开标冻结的资格版本缺失，无法转批复"));

        var currentEstimate = flowRepository.findCurrentEstimate(caseId);
        if (currentEstimate == null) {
            throw ApiException.unprocessable("原案无当前估价版本，无法转修理批复");
        }
        java.time.LocalDate approvalDate = request.approvalDate() != null
                ? request.approvalDate() : java.time.LocalDate.now(clock);
        long approvalId = flowRepository.insertApproval(caseId, currentEstimate.id(), approvalDate, now);
        caseRepository.updateState(caseId, CaseStatus.APPROVED, Determination.REPAIR, now);

        AwardRow award = new AwardRow(0, caseId, inquiryId, opening.id(), winnerBid.id(), supplier.id(),
                supplier.code(), frozenQual.version(), frozenQual.validFrom(), frozenQual.validTo(),
                request.reviewer1(), request.reviewer2(), approvalId, request.requestId());
        long awardId = bidRepository.insertAward(award, now);
        inquiryRepository.updateStatus(inquiryId, InquiryStatus.FROZEN);

        AwardView view = new AwardView(awardId, caseId, inquiryId, opening.id(), winnerBid.id(),
                supplier.code(), frozenQual.version(), frozenQual.validFrom(), frozenQual.validTo(),
                request.reviewer1(), request.reviewer2(), approvalId, approvalDate);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ==================== 查询 ====================

    /** 供应商明细（含适用工法与最近资格版本），不存在 404 */
    @Transactional(readOnly = true)
    public SupplierView supplierDetail(String supplierCode) {
        return loadSupplierView(supplierCode);
    }

    /** 询价版本明细（固定项与允许工法），不存在 404 */
    @Transactional(readOnly = true)
    public InquiryView inquiryDetail(long inquiryId) {
        return loadInquiryView(inquiryId);
    }

    /** 询价版本全部报价（含历史修订与冻结状态） */
    @Transactional(readOnly = true)
    public List<BidView> listBids(long inquiryId) {
        inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        return bidRepository.listBids(inquiryId).stream().map(this::loadBidView).toList();
    }

    /** 开标清单；未开标返回 404 */
    @Transactional(readOnly = true)
    public OpeningView openingDetail(long inquiryId) {
        inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        return loadOpeningView(bidRepository.findOpening(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本尚未开标: " + inquiryId)));
    }

    /** 案件中标转批复凭证；未复核返回 404 */
    @Transactional(readOnly = true)
    public AwardView awardDetail(long caseId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        AwardRow row = bidRepository.findAwardByCase(caseId)
                .orElseThrow(() -> ApiException.notFound("该残损案暂无中标转批复凭证: " + caseId));
        java.time.LocalDate approvalDate = flowRepository.listApprovals(caseId).stream()
                .filter(a -> a.id() == row.approvalId())
                .findFirst()
                .map(a -> a.approvalDate())
                .orElseThrow();
        return new AwardView(row.id(), row.caseId(), row.inquiryId(), row.openingId(), row.winnerBidId(),
                row.supplierCode(), row.qualificationVersion(), row.qualValidFrom(), row.qualValidTo(),
                row.reviewer1(), row.reviewer2(), row.approvalId(), approvalDate);
    }

    // ==================== 内部装配与校验 ====================

    private record ValidatedBidItem(ItemRow item, BidItemInput input) {
    }

    private void validateQualWindow(LocalDateTime validFrom, LocalDateTime validTo) {
        if (validFrom == null || validTo == null) {
            throw ApiException.badRequest("资格有效期起止时刻不能为空");
        }
        if (!validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("资格失效时刻必须晚于生效时刻");
        }
    }

    private SupplierRow lockSupplier(String code) {
        return supplierRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + code));
    }

    private InquiryRow lockInquiry(long inquiryId) {
        return inquiryRepository.findByIdForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
    }

    private CaseRow lockCase(long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private void bumpVersion(SupplierRow supplier, LocalDateTime now) {
        int updated = supplierRepository.bumpVersionIfMatch(supplier.id(), supplier.version(), now);
        if (updated == 0) {
            throw ApiException.conflict("供应商版本被并发修改，请重试");
        }
    }

    /** 开标时刻资格失效的排除原因：撤销优先于到期 */
    private String exclusionReason(long supplierId, LocalDateTime at) {
        QualRow latest = supplierRepository.latestQualification(supplierId).orElse(null);
        if (latest != null && latest.status() == QualificationStatus.REVOKED
                && (latest.revokedAt() == null || !latest.revokedAt().isAfter(at))) {
            return REASON_REVOKED;
        }
        return REASON_EXPIRED;
    }

    private SupplierView loadSupplierView(String code) {
        SupplierRow supplier = supplierRepository.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + code));
        List<String> methods = supplierRepository.listMethods(supplier.id());
        QualRow qual = supplierRepository.latestQualification(supplier.id()).orElse(null);
        return new SupplierView(supplier.id(), supplier.code(), supplier.name(), supplier.version(),
                supplier.status().name(), methods, toQualView(qual));
    }

    private QualificationView toQualView(QualRow row) {
        if (row == null) {
            return null;
        }
        return new QualificationView(row.version(), row.validFrom(), row.validTo(), row.status().name(),
                row.revokedAt(), row.revokeReason());
    }

    private InquiryView loadInquiryView(long inquiryId) {
        InquiryRow inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价版本不存在: " + inquiryId));
        List<InquiryItemView> items = inquiryRepository.listItems(inquiryId).stream()
                .map(item -> new InquiryItemView(item.id(), item.seqNo(), item.damageItemId(),
                        item.iiclCode(), item.component(), inquiryRepository.listItemMethods(item.id())))
                .toList();
        return new InquiryView(inquiry.id(), inquiry.caseId(), inquiry.version(), inquiry.deadline(),
                inquiry.status().name(), items);
    }

    private BidView loadBidView(BidRow bid) {
        List<ItemRow> inquiryItems = inquiryRepository.listItems(bid.inquiryId());
        Map<Long, Integer> seqByItem = new HashMap<>();
        for (ItemRow item : inquiryItems) {
            seqByItem.put(item.id(), item.seqNo());
        }
        List<BidItemRow> rows = bidRepository.listBidItems(bid.id());
        List<BidItemView> items = rows.stream()
                .map(r -> new BidItemView(r.inquiryItemId(),
                        seqByItem.getOrDefault(r.inquiryItemId(), 0), r.methodCode(), r.amountCents()))
                .toList();
        String supplierCode = supplierRepository.findById(bid.supplierId())
                .map(SupplierRow::code).orElse("?");
        return new BidView(bid.id(), bid.inquiryId(), supplierCode, bid.revision(), bid.totalAmountCents(),
                bid.status().name(), bid.excludeReason(), items);
    }

    private OpeningView loadOpeningView(OpeningRow opening) {
        List<OpeningLineRow> rows = bidRepository.listOpeningLines(opening.id());
        List<OpeningLineView> lines = rows.stream()
                .map(r -> new OpeningLineView(r.bidId(), r.supplierCode(), r.revision(),
                        r.totalAmountCents(), r.qualified(), r.qualificationVersion(),
                        r.excludeReason(), r.rankNo()))
                .toList();
        Integer winnerRevision = null;
        Long winnerAmount = null;
        if (opening.winnerBidId() != null) {
            BidRow winner = bidRepository.findBidById(opening.winnerBidId()).orElseThrow();
            winnerRevision = winner.revision();
            winnerAmount = winner.totalAmountCents();
        }
        return new OpeningView(opening.id(), opening.inquiryId(), opening.openedAt(),
                opening.winnerSupplierCode(), opening.winnerBidId(), winnerRevision, winnerAmount, lines);
    }
}
