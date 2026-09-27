package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.dto.SealedRequests.BidLineInput;
import com.example.starter.damage.dto.SealedRequests.OpenBidsRequest;
import com.example.starter.damage.dto.SealedRequests.PublishInquiryRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRenewRequest;
import com.example.starter.damage.dto.SealedRequests.QualificationRevokeRequest;
import com.example.starter.damage.dto.SealedRequests.ReviewRequest;
import com.example.starter.damage.dto.SealedRequests.SubmitBidRequest;
import com.example.starter.damage.dto.SealedViews.BidLineView;
import com.example.starter.damage.dto.SealedViews.BidView;
import com.example.starter.damage.dto.SealedViews.InquiryItemView;
import com.example.starter.damage.dto.SealedViews.InquiryView;
import com.example.starter.damage.dto.SealedViews.OpeningBidView;
import com.example.starter.damage.dto.SealedViews.OpeningView;
import com.example.starter.damage.dto.SealedViews.QualificationView;
import com.example.starter.damage.dto.SealedViews.RevokeResultView;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.SealedBidRepository;
import com.example.starter.damage.store.SealedBidRepository.BidRow;
import com.example.starter.damage.store.SealedBidRepository.InquiryRow;
import com.example.starter.damage.store.SealedBidRepository.OpeningRow;
import com.example.starter.damage.store.SealedBidRepository.QualificationRow;
import com.example.starter.damage.store.SealedBidRepository.SupplierRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 密封报价事务内业务服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>询价版本固定残损项集合、每项允许工法与 UTC 截止时刻；版本从 1 开始，成功发布加 1；</li>
 *   <li>now&lt;截止方可投标，恰在截止时刻拒绝；报价须覆盖全部项、金额为正整数分、工法有资格，任一不符整版拒绝；</li>
 *   <li>一供应商一询价版本仅一个当前报价，修订保留历史；</li>
 *   <li>截止后开标冻结全部当前报价，按开标时刻重算资格有效性（不沿用投标时旧资格），
 *       最低价中标、同价按供应商编号升序；零有效报价不开批复；</li>
 *   <li>双人不同复核才将中标报价转为原案修理批复，并冻结询价/报价；原全损判定仍适用；</li>
 *   <li>重复开标返回首次清单，不因后来资格变化重算；</li>
 *   <li>批复后撤销资格只形成风险事件，不改写历史中标证据；批复前撤销在竞争/转换时一致生效。</li>
 * </ul>
 */
@Service
public class SealedBidTxService {

    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final SealedBidRepository sealedRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public SealedBidTxService(DamageCaseRepository caseRepository,
                              DamageFlowRepository flowRepository,
                              SealedBidRepository sealedRepository,
                              IdempotencyExecutor idempotency,
                              Clock clock) {
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.sealedRepository = sealedRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 发布询价版本：expectedVersion 为本次成功发布将产生的版本号（首次为 1）。
     * 同一案件同时刻只允许一个 OPEN 询价版本；零有效报价开标后可发新版本。
     */
    @Transactional
    public InquiryView publishInquiry(long caseId, PublishInquiryRequest request) {
        CaseRow caseRow = lockCase(caseId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(request.deadline())) {
            throw ApiException.unprocessable("报价截止时刻必须晚于当前时刻（UTC）");
        }
        if (caseRow.status() != CaseStatus.ESTIMATED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable(
                    "仅判定为修理且待批复的残损案可发布密封询价，当前状态: " + caseRow.status());
        }
        if (flowRepository.findCurrentEstimate(caseId) == null) {
            throw ApiException.unprocessable("残损案无有效估价版本，无法发布询价");
        }
        Optional<InquiryRow> latest = sealedRepository.findLatestInquiryForUpdate(caseId);
        int nextVersion = latest.map(i -> i.version() + 1).orElse(1);
        if (request.expectedVersion() != nextVersion) {
            throw ApiException.conflict("询价 expectedVersion 不匹配，下一版本应为: " + nextVersion);
        }
        if (latest.isPresent() && "OPEN".equals(latest.get().status())) {
            throw ApiException.unprocessable("该案件已有投标中的询价版本，请先开标或等待截止");
        }

        Set<Long> seenItemIds = new HashSet<>();
        List<InquiryItemView> items = new ArrayList<>();
        int seq = 1;
        for (var input : request.items()) {
            if (!seenItemIds.add(input.itemId())) {
                throw ApiException.badRequest("询价残损项重复: itemId=" + input.itemId());
            }
            var caseItem = sealedRepository.findCaseItem(caseId, input.itemId())
                    .orElseThrow(() -> ApiException.badRequest(
                            "残损项不属于该案件或不存在: itemId=" + input.itemId()));
            Set<String> methods = new HashSet<>();
            for (String method : input.allowedMethods()) {
                String code = method == null ? "" : method.trim();
                if (!methods.add(code)) {
                    throw ApiException.badRequest("工法在同一残损项内重复: " + code);
                }
                if (!sealedRepository.repairMethodExists(code)) {
                    throw ApiException.badRequest("未知修箱工法代码: " + code);
                }
            }
            items.add(new InquiryItemView(seq++, input.itemId(),
                    caseItem.iiclCode(), caseItem.component(), new ArrayList<>(methods)));
        }

        long inquiryId;
        try {
            inquiryId = sealedRepository.insertInquiry(caseId, nextVersion, request.deadline(),
                    request.requestId(), now, items);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("询价版本已存在（版本冲突）: " + nextVersion);
        }
        InquiryView view = sealedRepository.toInquiryView(
                sealedRepository.findInquiryById(inquiryId).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 提交/修订报价。expectedVersion 为本次成功提交将产生的报价修订号（首次为 1，修订为 2…）。
     * 报价须覆盖询价全部项、金额正整数分、工法在允许集合且当前资格覆盖，任一不符整版拒绝。
     */
    @Transactional
    public BidView submitBid(long caseId, int inquiryVersion, SubmitBidRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(caseId, inquiryVersion);
        if (!"OPEN".equals(inquiry.status())) {
            throw ApiException.unprocessable("询价版本已冻结，不可投标: " + inquiry.status());
        }
        if (!now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("已过报价截止时刻（UTC），不再受理报价（恰在截止时刻同样拒绝）");
        }
        SupplierRow supplier = sealedRepository.findSupplierByNo(request.supplierNo())
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + request.supplierNo()));

        List<InquiryItemView> inquiryItems = sealedRepository.listInquiryItems(inquiry.id());
        Map<Integer, BidLineInput> bySeq = new HashMap<>();
        long totalCents = 0;
        for (BidLineInput line : request.lines()) {
            if (bySeq.put(line.itemSeq(), line) != null) {
                throw ApiException.badRequest("报价分项序号重复: " + line.itemSeq());
            }
            if (line.itemSeq() < 1 || line.itemSeq() > inquiryItems.size()) {
                throw ApiException.unprocessable("报价分项序号超出询价范围: " + line.itemSeq());
            }
            if (line.priceCents() == null || line.priceCents() <= 0) {
                throw ApiException.badRequest("报价金额必须为正整数分");
            }
            totalCents = Math.addExact(totalCents, line.priceCents());
        }
        if (bySeq.size() != inquiryItems.size()) {
            throw ApiException.unprocessable(
                    "报价须覆盖全部残损项，应收 " + inquiryItems.size() + " 项，实收 " + bySeq.size() + " 项");
        }

        // 投标时刻资格校验：须存在有效（含起止时刻）的 ACTIVE 资格版本，且覆盖所有报价工法。
        QualificationRow qualification = sealedRepository.findLatestQualification(supplier.id())
                .orElseThrow(() -> ApiException.unprocessable("供应商无有效资格，整版拒绝"));
        if (!isQualificationActive(qualification, now)) {
            throw ApiException.unprocessable("供应商资格在投标时刻已失效，整版拒绝");
        }
        Set<String> qualifiedMethods = new HashSet<>(sealedRepository.qualificationMethods(qualification.id()));

        List<BidLineView> lines = new ArrayList<>();
        for (InquiryItemView inquiryItem : inquiryItems) {
            BidLineInput line = bySeq.get(inquiryItem.itemSeq());
            String method = line.methodCode() == null ? "" : line.methodCode().trim();
            if (!inquiryItem.allowedMethods().contains(method)) {
                throw ApiException.unprocessable(
                        "第 " + inquiryItem.itemSeq() + " 项工法不在询价允许集合内，整版拒绝: " + method);
            }
            if (!qualifiedMethods.contains(method)) {
                throw ApiException.unprocessable(
                        "供应商资格不覆盖第 " + inquiryItem.itemSeq() + " 项工法 " + method + "，整版拒绝");
            }
            lines.add(new BidLineView(inquiryItem.itemSeq(), method, line.priceCents()));
        }

        Optional<BidRow> current = sealedRepository.findCurrentBidForUpdate(inquiry.id(), supplier.id());
        int nextRevision = current.map(b -> b.revision() + 1).orElse(1);
        if (request.expectedVersion() == null || request.expectedVersion() != nextRevision) {
            throw ApiException.conflict("报价 expectedVersion 不匹配，下一修订号应为: " + nextRevision);
        }
        current.ifPresent(b -> sealedRepository.supersedeCurrentBid(b.id()));
        long bidId;
        try {
            bidId = sealedRepository.insertBid(inquiry.id(), supplier.id(), nextRevision,
                    totalCents, request.requestId(), now, lines);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("报价修订号冲突（并发投标）: " + nextRevision);
        }
        BidView view = sealedRepository.toBidView(
                sealedRepository.findBidById(bidId).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 截止后开标：冻结全部当前报价并一次性写入中标/落标/排除结论。
     * 资格按开标时刻重算（不沿用投标时旧资格）；重复开标（含不同 requestId）原样返回首次清单。
     */
    @Transactional
    public OpeningView openBids(long caseId, int inquiryVersion, OpenBidsRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(caseId, inquiryVersion);
        if (now.isBefore(inquiry.deadline()) || now.isEqual(inquiry.deadline())) {
            throw ApiException.unprocessable("尚未到报价截止时刻（UTC），不能开标");
        }
        Optional<OpeningRow> existing = sealedRepository.findOpeningForUpdate(inquiry.id());
        if (existing.isPresent()) {
            // 重复开标（不同 requestId）：返回首次冻结清单，不因后来资格变化重算，仍完成幂等占位。
            OpeningView replay = buildOpeningView(inquiry, existing.get());
            idempotency.completeInTransaction(request.requestId(), replay);
            return replay;
        }

        sealedRepository.markInquiryOpened(inquiry.id(), now);
        List<BidRow> bids = sealedRepository.listCurrentBidsForUpdate(inquiry.id());

        // 开标时刻资格评估：逐供应商取最新资格版本，失效或不覆盖工法均排除并记录原因。
        Map<Long, EvalResult> evaluations = new HashMap<>();
        for (BidRow bid : bids) {
            evaluations.put(bid.id(), evaluateAtOpening(bid, now));
        }
        List<BidRow> validBids = bids.stream()
                .filter(b -> evaluations.get(b.id()).qualified)
                .sorted(Comparator.comparingLong(BidRow::totalCents)
                        .thenComparing(b -> b.supplierNo()))
                .toList();

        BidRow winner = validBids.isEmpty() ? null : validBids.get(0);
        long openingId = sealedRepository.insertOpening(inquiry.id(),
                winner == null ? null : winner.id(),
                winner == null ? null : winner.supplierId(),
                winner == null ? null : winner.totalCents(),
                winner == null ? "NO_VALID_BID" : "SELECTED",
                now, request.requestId());

        for (BidRow bid : bids) {
            EvalResult eval = evaluations.get(bid.id());
            if (bid == winner) {
                sealedRepository.freezeBid(bid.id(), "WINNER", null, eval.qualificationId);
            } else if (eval.qualified) {
                sealedRepository.freezeBid(bid.id(), "LOST", null, eval.qualificationId);
            } else {
                sealedRepository.freezeBid(bid.id(), "EXCLUDED", eval.reason, eval.qualificationId);
                sealedRepository.insertExclusion(openingId, bid.id(), bid.supplierId(), eval.reason);
            }
        }

        OpeningView view = buildOpeningView(inquiry,
                sealedRepository.findOpening(inquiry.id()).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 中标双人不同复核：同一复核人重复复核拒绝；第二名不同复核人提交时，
     * 再次校验中标资格有效性，通过后将报价转为原案修理批复，冻结询价、报价与采用资格证据。
     */
    @Transactional
    public OpeningView review(long caseId, int inquiryVersion, ReviewRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        InquiryRow inquiry = lockInquiry(caseId, inquiryVersion);
        OpeningRow opening = sealedRepository.findOpeningForUpdate(inquiry.id())
                .orElseThrow(() -> ApiException.unprocessable("该询价版本尚未开标，无法复核"));
        if ("NO_VALID_BID".equals(opening.status())) {
            throw ApiException.unprocessable("零有效报价的开标不能复核或转批复");
        }
        if (opening.approvalId() != null) {
            // 已转换：重复复核返回冻结结果，仍完成幂等占位。
            OpeningView replay = buildOpeningView(inquiry, opening);
            idempotency.completeInTransaction(request.requestId(), replay);
            return replay;
        }
        if (sealedRepository.reviewExists(opening.id(), request.reviewer())) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }
        sealedRepository.insertReview(opening.id(), request.reviewer(), request.requestId(), now);

        if (sealedRepository.listReviewers(opening.id()).size() < 2) {
            // 仅第一名复核人：记录复核，尚未转批复。
            OpeningView pendingView = buildOpeningView(inquiry,
                    sealedRepository.findOpening(inquiry.id()).orElseThrow());
            idempotency.completeInTransaction(request.requestId(), pendingView);
            return pendingView;
        }

        // 第二名不同复核人：转换前再次确认中标资格在转换时刻仍有效（撤销与竞争一致）。
        BidRow winnerBid = sealedRepository.findBidById(
                opening.winnerBidId() == null ? 0 : opening.winnerBidId())
                .orElseThrow(() -> ApiException.unprocessable("中标报价证据缺失，无法转批复"));
        QualificationRow winnerQual = winnerBid.qualificationId() == null
                ? null
                : sealedRepository.findQualification(winnerBid.qualificationId()).orElse(null);
        if (winnerQual == null || !isQualificationActive(winnerQual, now)) {
            throw ApiException.unprocessable("中标供应商资格在转批复前已失效或被撤销，竞争结果不得转批复");
        }
        CaseRow caseRow = lockCase(caseId);
        if (caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("原案全损判定仍适用，不得转为修理批复");
        }
        var currentEstimate = flowRepository.findCurrentEstimate(caseId);
        if (currentEstimate == null) {
            throw ApiException.unprocessable("原案无有效估价版本，无法转修理批复");
        }

        long approvalId = flowRepository.insertApproval(caseId, currentEstimate.id(),
                now.toLocalDate(), now);
        caseRepository.updateState(caseId, CaseStatus.APPROVED, Determination.REPAIR, now);
        sealedRepository.markOpeningConverted(opening.id(), approvalId);
        sealedRepository.markInquiryConverted(inquiry.id(), approvalId);

        OpeningView view = buildOpeningView(inquiry,
                sealedRepository.findOpening(inquiry.id()).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 供应商资格续期：新版本只追加，expectedVersion 为将产生的新版本号（种子版本为 1，首次续期为 2）。
     */
    @Transactional
    public QualificationView renewQualification(String supplierNo, QualificationRenewRequest request) {
        SupplierRow supplier = sealedRepository.findSupplierByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        sealedRepository.lockSupplier(supplier.id());
        LocalDateTime now = LocalDateTime.now(clock);
        if (!request.validFrom().isBefore(request.validUntil()) && !request.validFrom().isEqual(request.validUntil())) {
            throw ApiException.badRequest("资格有效期起点不得晚于终点");
        }
        Set<String> methods = new HashSet<>();
        for (String method : request.methods()) {
            String code = method == null ? "" : method.trim();
            if (!methods.add(code)) {
                throw ApiException.badRequest("适用工法重复: " + code);
            }
            if (!sealedRepository.repairMethodExists(code)) {
                throw ApiException.badRequest("未知修箱工法代码: " + code);
            }
        }
        Optional<QualificationRow> latest = sealedRepository.findLatestQualificationForUpdate(supplier.id());
        int nextVersion = latest.map(q -> q.version() + 1).orElse(1);
        if (request.expectedVersion() == null || request.expectedVersion() != nextVersion) {
            throw ApiException.conflict("资格 expectedVersion 不匹配，下一版本应为: " + nextVersion);
        }
        long qualificationId;
        try {
            qualificationId = sealedRepository.insertQualification(supplier.id(), nextVersion,
                    request.validFrom(), request.validUntil(), request.requestId(), now, new ArrayList<>(methods));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("资格版本冲突（并发续期）: " + nextVersion);
        }
        QualificationView view = sealedRepository.toQualificationView(supplierNo,
                sealedRepository.findQualification(qualificationId).orElseThrow());
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 撤销供应商当前（最新版本）资格。
     * 开标在开标时刻重算资格，故撤销对尚未开标的竞争自动生效；
     * 已转批复（中标证据冻结）后撤销只形成 SUPPLIER_RISK 风险事件，不改写历史中标证据。
     */
    @Transactional
    public RevokeResultView revokeQualification(String supplierNo, QualificationRevokeRequest request) {
        SupplierRow supplier = sealedRepository.findSupplierByNo(supplierNo)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierNo));
        sealedRepository.lockSupplier(supplier.id());
        LocalDateTime now = LocalDateTime.now(clock);
        QualificationRow latest = sealedRepository.findLatestQualificationForUpdate(supplier.id())
                .orElseThrow(() -> ApiException.notFound("供应商尚无资格版本: " + supplierNo));

        // 已转批复的中标：撤销只登记风险事件，资格与中标证据均不改写。
        if (sealedRepository.existsConvertedWinning(supplier.id())) {
            for (Long caseId : sealedRepository.listConvertedWinningCaseIds(supplier.id())) {
                flowRepository.insertEvent(caseId, "SUPPLIER_RISK",
                        "供应商 " + supplierNo + " 资格事后撤销，中标批复保留: qualificationId=" + latest.id(),
                        now);
            }
            QualificationView view = sealedRepository.toQualificationView(supplierNo, latest);
            RevokeResultView result = new RevokeResultView("RISK_RECORDED", view);
            idempotency.completeInTransaction(request.requestId(), result);
            return result;
        }

        if ("REVOKED".equals(latest.status())) {
            // 重复撤销幂等返回。
            RevokeResultView result = new RevokeResultView("REVOKED",
                    sealedRepository.toQualificationView(supplierNo, latest));
            idempotency.completeInTransaction(request.requestId(), result);
            return result;
        }
        sealedRepository.revokeQualification(latest.id(), now);
        QualificationView view = sealedRepository.toQualificationView(supplierNo,
                sealedRepository.findQualification(latest.id()).orElseThrow());
        RevokeResultView result = new RevokeResultView("REVOKED", view);
        idempotency.completeInTransaction(request.requestId(), result);
        return result;
    }

    /** 查询询价版本（含固定项与允许工法），不存在 404 */
    @Transactional(readOnly = true)
    public InquiryView inquiry(long caseId, int inquiryVersion) {
        requireCase(caseId);
        return sealedRepository.toInquiryView(
                sealedRepository.findInquiry(caseId, inquiryVersion)
                        .orElseThrow(() -> ApiException.notFound(
                                "询价版本不存在: caseId=" + caseId + ", version=" + inquiryVersion)));
    }

    /** 查询开标清单（首次冻结结果），未开标 404 */
    @Transactional(readOnly = true)
    public OpeningView opening(long caseId, int inquiryVersion) {
        InquiryRow inquiry = sealedRepository.findInquiry(caseId, inquiryVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "询价版本不存在: caseId=" + caseId + ", version=" + inquiryVersion));
        OpeningRow opening = sealedRepository.findOpening(inquiry.id())
                .orElseThrow(() -> ApiException.notFound("该询价版本尚未开标"));
        return buildOpeningView(inquiry, opening);
    }

    // ==================== 内部辅助 ====================

    /** 开标时刻对单个报价的资格评估结果 */
    private record EvalResult(boolean qualified, String reason, Long qualificationId) {
    }

    private EvalResult evaluateAtOpening(BidRow bid, LocalDateTime now) {
        QualificationRow qualification = sealedRepository.findLatestQualification(bid.supplierId())
                .orElse(null);
        if (qualification == null) {
            return new EvalResult(false, "QUALIFICATION_MISSING", null);
        }
        if (!"ACTIVE".equals(qualification.status())) {
            // 开标竞争与撤销一致：已撤销版本在开标时直接排除，即便报价提交时仍然有效。
            return new EvalResult(false, "QUALIFICATION_REVOKED", qualification.id());
        }
        if (now.isBefore(qualification.validFrom()) || now.isAfter(qualification.validUntil())) {
            // 不采用投标时旧资格：即便报价提交时有效，开标时超出有效期同样排除。
            return new EvalResult(false, "QUALIFICATION_EXPIRED", qualification.id());
        }
        Set<String> qualifiedMethods = new HashSet<>(
                sealedRepository.qualificationMethods(qualification.id()));
        for (BidLineView line : sealedRepository.listBidItems(bid.id())) {
            if (!qualifiedMethods.contains(line.methodCode())) {
                return new EvalResult(false, "METHOD_NOT_COVERED", qualification.id());
            }
        }
        return new EvalResult(true, null, qualification.id());
    }

    private boolean isQualificationActive(QualificationRow qualification, LocalDateTime now) {
        return "ACTIVE".equals(qualification.status())
                && !now.isBefore(qualification.validFrom())
                && !now.isAfter(qualification.validUntil());
    }

    private OpeningView buildOpeningView(InquiryRow inquiry, OpeningRow opening) {
        String winnerSupplierNo = null;
        if (opening.winnerSupplierId() != null) {
            winnerSupplierNo = sealedRepository.findSupplierById(opening.winnerSupplierId())
                    .map(SupplierRow::supplierNo).orElse(null);
        }
        List<OpeningBidView> rankedBids = sealedRepository.listOpeningBids(opening.id());
        List<String> reviewers = sealedRepository.listReviewers(opening.id());
        return new OpeningView(
                opening.id(), inquiry.id(), inquiry.version(), inquiry.caseId(),
                opening.status(), opening.winnerBidId(), winnerSupplierNo, opening.totalCents(),
                opening.approvalId(), opening.openedAt(), rankedBids, reviewers,
                opening.approvalId() != null);
    }

    private CaseRow lockCase(long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private void requireCase(long caseId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private InquiryRow lockInquiry(long caseId, int inquiryVersion) {
        lockCase(caseId);
        return sealedRepository.findInquiryForUpdate(caseId, inquiryVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "询价版本不存在: caseId=" + caseId + ", version=" + inquiryVersion));
    }
}
