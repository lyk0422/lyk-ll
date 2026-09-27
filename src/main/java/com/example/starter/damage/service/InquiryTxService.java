package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.dto.Requests.BidOpenRequest;
import com.example.starter.damage.dto.Requests.InquiryItemInput;
import com.example.starter.damage.dto.Requests.InquiryPublishRequest;
import com.example.starter.damage.dto.Requests.QuotationLineInput;
import com.example.starter.damage.dto.Requests.QuotationReviseRequest;
import com.example.starter.damage.dto.Requests.QuotationSubmitRequest;
import com.example.starter.damage.dto.Requests.ReviewRequest;
import com.example.starter.damage.dto.Views.BidOpeningEntryView;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.InquiryDetailView;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.QuotationView;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
import com.example.starter.damage.store.InquiryRepository.OpeningRow;
import com.example.starter.damage.store.InquiryRepository.QuotationRow;
import com.example.starter.damage.store.SupplierRepository;
import com.example.starter.damage.store.SupplierRepository.QualificationRow;
import com.example.starter.damage.store.SupplierRepository.SupplierRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 密封询价事务内业务服务：发布询价、投标与修订、截止后开标、双人复核定标。
 * 开标以开标时刻的资格状态评估；定标将中标报价转为原案修理批复并冻结询价、报价与资格版本。
 */
@Service
public class InquiryTxService {

    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final InquiryRepository inquiryRepository;
    private final SupplierRepository supplierRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public InquiryTxService(DamageCaseRepository caseRepository,
                            DamageFlowRepository flowRepository,
                            InquiryRepository inquiryRepository,
                            SupplierRepository supplierRepository,
                            IdempotencyExecutor idempotency,
                            Clock clock) {
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.inquiryRepository = inquiryRepository;
        this.supplierRepository = supplierRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** 发布询价版本：待批复残损案（已估价且判定修理）方可发布；固定项集合、允许工法与 UTC 截止时刻 */
    @Transactional
    public InquiryDetailView publish(long caseId, InquiryPublishRequest request) {
        CaseRow row = lockCase(caseId);
        if (row.status() != CaseStatus.ESTIMATED || row.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("仅已估价且判定修理的待批复残损案可发布询价，当前状态: "
                    + row.status() + "，判定: " + row.determination());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!request.deadline().isAfter(now)) {
            throw ApiException.badRequest("报价截止时刻须晚于当前时刻（UTC）");
        }
        Set<Long> caseItemIds = flowRepository.listItems(caseId).stream()
                .map(item -> item.id()).collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (InquiryItemInput item : request.items()) {
            if (!seen.add(item.itemId())) {
                throw ApiException.badRequest("询价项重复: " + item.itemId());
            }
            if (!caseItemIds.contains(item.itemId())) {
                throw ApiException.unprocessable("询价项须为本案残损项: " + item.itemId());
            }
            for (String method : new LinkedHashSet<>(item.methods())) {
                if (!supplierRepository.methodExists(method)) {
                    throw ApiException.badRequest("未知工法代码: " + method);
                }
            }
        }
        List<InquiryView> existing = inquiryRepository.listInquiries(caseId);
        if (existing.stream().anyMatch(i -> "AWARDED".equals(i.status()))) {
            throw ApiException.unprocessable("案件已定标，不得再发布询价");
        }
        inquiryRepository.supersedeOpenInquiries(caseId);
        long inquiryId = inquiryRepository.insertInquiry(caseId, request.deadline(), request.requestId(), now);
        for (InquiryItemInput item : request.items()) {
            long inquiryItemId = inquiryRepository.insertInquiryItem(inquiryId, item.itemId());
            for (String method : new LinkedHashSet<>(item.methods())) {
                inquiryRepository.insertInquiryItemMethod(inquiryItemId, method);
            }
        }
        InquiryDetailView view = detail(inquiryId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 投标：now<截止方可投标，恰在截止拒绝；报价须覆盖全部项、金额为正整数分、工法有资格，任一不符整版拒绝 */
    @Transactional
    public QuotationView submit(long inquiryId, QuotationSubmitRequest request) {
        InquiryView inquiry = lockOpenInquiry(inquiryId);
        requireBeforeDeadline(inquiry);
        SupplierRow supplier = lockSupplier(request.supplierId());
        Set<String> supplierMethods = requireActiveQualification(supplier);
        validateLines(inquiryId, request.lines(), supplierMethods);
        if (inquiryRepository.findCurrentQuotation(inquiryId, request.supplierId()) != null) {
            throw ApiException.unprocessable("该供应商已存在当前报价，须走修订");
        }
        long total = request.lines().stream().mapToLong(QuotationLineInput::amountCents).sum();
        long quotationId;
        try {
            quotationId = inquiryRepository.insertQuotation(inquiryId, request.supplierId(), 1,
                    total, request.requestId(), LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("该供应商已存在当前报价，须走修订");
        }
        insertLines(quotationId, request.lines());
        QuotationView view = loadQuotationView(quotationId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 修订报价：expectedVersion 须等于当前报价版本，旧版本保留历史 */
    @Transactional
    public QuotationView revise(long inquiryId, long supplierId, QuotationReviseRequest request) {
        InquiryView inquiry = lockOpenInquiry(inquiryId);
        requireBeforeDeadline(inquiry);
        SupplierRow supplier = lockSupplier(supplierId);
        Set<String> supplierMethods = requireActiveQualification(supplier);
        QuotationRow current = inquiryRepository.findCurrentQuotation(inquiryId, supplierId);
        if (current == null) {
            throw ApiException.notFound("该供应商在询价 " + inquiryId + " 下无当前报价");
        }
        if (request.expectedVersion() == null || request.expectedVersion() != current.version()) {
            throw ApiException.conflict("版本不一致：期望 " + request.expectedVersion()
                    + "，当前 " + current.version());
        }
        validateLines(inquiryId, request.lines(), supplierMethods);
        long total = request.lines().stream().mapToLong(QuotationLineInput::amountCents).sum();
        inquiryRepository.supersedeCurrentQuotation(inquiryId, supplierId);
        long quotationId = inquiryRepository.insertQuotation(inquiryId, supplierId,
                current.version() + 1, total, request.requestId(), LocalDateTime.now(clock));
        insertLines(quotationId, request.lines());
        QuotationView view = loadQuotationView(quotationId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 开标：截止后冻结全部有效报价，按总价最低、同价按供应商编号升序定标；
     * 开标时资格已失效的报价排除并列明原因；重复开标返回首次清单，不重算。
     */
    @Transactional
    public BidOpeningView open(long inquiryId, BidOpenRequest request) {
        InquiryView inquiry = lockInquiry(inquiryId);
        OpeningRow existing = inquiryRepository.findOpening(inquiryId);
        if (existing != null) {
            BidOpeningView replay = toOpeningView(existing);
            idempotency.completeInTransaction(request.requestId(), replay);
            return replay;
        }
        if (!"OPEN".equals(inquiry.status())) {
            throw ApiException.unprocessable("当前询价状态不允许开标: " + inquiry.status());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("未到报价截止时刻，不得开标");
        }
        List<QuotationRow> quotations = inquiryRepository.listCurrentQuotations(inquiryId);
        record Evaluated(QuotationRow quotation, SupplierRow supplier, QualificationRow qualification,
                         boolean included, String reason) {
        }
        List<Evaluated> evaluated = new ArrayList<>();
        for (QuotationRow quotation : quotations) {
            SupplierRow supplier = lockSupplier(quotation.supplierId());
            QualificationRow qualification = supplierRepository
                    .findCurrentQualification(quotation.supplierId(), supplier.version());
            String reason = null;
            if (qualification == null || !"ACTIVE".equals(qualification.status())) {
                reason = "开标时资格已撤销";
            } else if (now.isBefore(qualification.validFrom())) {
                reason = "开标时资格尚未生效";
            } else if (now.isAfter(qualification.validUntil())) {
                reason = "开标时资格已过期";
            }
            evaluated.add(new Evaluated(quotation, supplier, qualification, reason == null, reason));
        }
        inquiryRepository.freezeCurrentQuotations(inquiryId);
        List<Evaluated> included = evaluated.stream().filter(Evaluated::included)
                .sorted(Comparator.comparingLong((Evaluated e) -> e.quotation().totalAmount())
                        .thenComparing(e -> e.supplier().supplierNo()))
                .toList();
        Long winnerId = included.isEmpty() ? null : included.get(0).quotation().id();
        long openingId = inquiryRepository.insertOpening(inquiryId, winnerId, now);
        Map<Long, Integer> ranks = new java.util.HashMap<>();
        for (int i = 0; i < included.size(); i++) {
            ranks.put(included.get(i).quotation().id(), i + 1);
        }
        for (Evaluated e : evaluated) {
            inquiryRepository.insertOpeningEntry(openingId, e.quotation().id(), e.supplier().id(),
                    e.supplier().supplierNo(), e.quotation().totalAmount(), e.included(), e.reason(),
                    e.qualification() == null ? null : e.qualification().version(),
                    ranks.get(e.quotation().id()));
        }
        inquiryRepository.updateInquiryStatus(inquiryId, "OPENED");
        BidOpeningView view = toOpeningView(inquiryRepository.findOpening(inquiryId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 定标复核：双人不同复核后将中标报价转为原案修理批复，
     * 并冻结询价、报价与资格版本；原全损判定仍适用，零有效报价不开批复。
     */
    @Transactional
    public InquiryDetailView review(long inquiryId, ReviewRequest request) {
        InquiryView inquiry = lockInquiry(inquiryId);
        if (!"OPENED".equals(inquiry.status())) {
            throw ApiException.unprocessable("当前询价状态不允许复核: " + inquiry.status());
        }
        OpeningRow opening = inquiryRepository.findOpening(inquiryId);
        if (opening == null || opening.winnerQuotationId() == null) {
            throw ApiException.unprocessable("零有效报价，不开批复");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            inquiryRepository.insertReview(inquiryId, request.reviewer(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("同一复核人不得重复复核，须双人不同复核");
        }
        if (inquiryRepository.countReviews(inquiryId) < 2) {
            InquiryDetailView pending = detail(inquiryId);
            idempotency.completeInTransaction(request.requestId(), pending);
            return pending;
        }
        CaseRow row = lockCase(inquiry.caseId());
        if (row.determination() != Determination.REPAIR || row.status() != CaseStatus.ESTIMATED) {
            throw ApiException.unprocessable("原案判定或状态已变化（全损判定仍适用），不得转修理批复，当前状态: "
                    + row.status() + "，判定: " + row.determination());
        }
        EstimateView currentEstimate = flowRepository.findCurrentEstimate(inquiry.caseId());
        if (currentEstimate == null) {
            throw ApiException.unprocessable("原案无有效估价版本，无法转修理批复");
        }
        flowRepository.insertApproval(inquiry.caseId(), currentEstimate.id(), LocalDate.now(clock), now);
        caseRepository.updateState(inquiry.caseId(), CaseStatus.APPROVED, row.determination(), now);
        inquiryRepository.updateInquiryStatus(inquiryId, "AWARDED");
        for (BidOpeningEntryView entry : inquiryRepository.listOpeningEntries(opening.id())) {
            if (entry.qualVersion() != null) {
                QualificationRow qualification = supplierRepository
                        .findQualificationByVersion(entry.supplierId(), entry.qualVersion());
                if (qualification != null) {
                    supplierRepository.freezeQualification(qualification.id());
                }
            }
        }
        InquiryDetailView view = detail(inquiryId);
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 询价全量明细：固定项、报价历史、开标结果与复核记录 */
    @Transactional(readOnly = true)
    public InquiryDetailView detail(long inquiryId) {
        InquiryView inquiry = inquiryRepository.findInquiry(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价不存在: " + inquiryId));
        List<InquiryItemView> items = inquiryRepository.listInquiryItems(inquiryId);
        List<QuotationView> quotations = inquiryRepository.listAllQuotations(inquiryId).stream()
                .map(inquiryRepository::toQuotationView)
                .toList();
        OpeningRow opening = inquiryRepository.findOpening(inquiryId);
        return new InquiryDetailView(inquiry, items, quotations,
                opening == null ? null : toOpeningView(opening),
                inquiryRepository.listReviews(inquiryId));
    }

    /** 案件全部询价版本 */
    @Transactional(readOnly = true)
    public List<InquiryView> listByCase(long caseId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        return inquiryRepository.listInquiries(caseId);
    }

    private void validateLines(long inquiryId, List<QuotationLineInput> lines, Set<String> supplierMethods) {
        Map<Long, InquiryItemView> inquiryItems = inquiryRepository.listInquiryItems(inquiryId).stream()
                .collect(Collectors.toMap(InquiryItemView::itemId, Function.identity()));
        Set<Long> covered = new HashSet<>();
        for (QuotationLineInput line : lines) {
            if (line.amountCents() == null || line.amountCents() <= 0) {
                throw ApiException.badRequest("报价金额须为正整数分");
            }
            if (!supplierRepository.methodExists(line.method())) {
                throw ApiException.badRequest("未知工法代码: " + line.method());
            }
            InquiryItemView item = inquiryItems.get(line.itemId());
            if (item == null) {
                throw ApiException.unprocessable("报价行须对应询价固定项: " + line.itemId());
            }
            if (!covered.add(line.itemId())) {
                throw ApiException.unprocessable("报价行重复，整版拒绝: " + line.itemId());
            }
            if (!item.methods().contains(line.method())) {
                throw ApiException.unprocessable("工法不在该项允许工法集合内: " + line.method());
            }
            if (!supplierMethods.contains(line.method())) {
                throw ApiException.unprocessable("供应商无该工法资格: " + line.method());
            }
        }
        if (!covered.equals(inquiryItems.keySet())) {
            throw ApiException.unprocessable("报价须覆盖全部询价项，缺一整版拒绝");
        }
    }

    private void insertLines(long quotationId, List<QuotationLineInput> lines) {
        for (QuotationLineInput line : lines) {
            inquiryRepository.insertQuotationLine(quotationId, line.itemId(), line.method(), line.amountCents());
        }
    }

    private InquiryView lockInquiry(long inquiryId) {
        return inquiryRepository.findInquiryForUpdate(inquiryId)
                .orElseThrow(() -> ApiException.notFound("询价不存在: " + inquiryId));
    }

    private InquiryView lockOpenInquiry(long inquiryId) {
        InquiryView inquiry = lockInquiry(inquiryId);
        if (!"OPEN".equals(inquiry.status())) {
            throw ApiException.unprocessable("当前询价状态不允许投标: " + inquiry.status());
        }
        return inquiry;
    }

    private void requireBeforeDeadline(InquiryView inquiry) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(inquiry.deadline())) {
            throw ApiException.unprocessable("已过报价截止时刻（恰在截止亦拒绝）");
        }
    }

    private CaseRow lockCase(long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
    }

    private SupplierRow lockSupplier(long supplierId) {
        return supplierRepository.findByIdForUpdate(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在: " + supplierId));
    }

    private Set<String> requireActiveQualification(SupplierRow supplier) {
        QualificationRow current = supplierRepository.findCurrentQualification(supplier.id(), supplier.version());
        if (current == null || !"ACTIVE".equals(current.status())) {
            throw ApiException.unprocessable("供应商资格已撤销，不得投标");
        }
        return new LinkedHashSet<>(supplierRepository.listQualMethods(current.id()));
    }

    private QuotationView loadQuotationView(long quotationId) {
        QuotationRow row = inquiryRepository.findQuotationById(quotationId);
        if (row == null) {
            throw new IllegalStateException("报价写入后读取失败: " + quotationId);
        }
        return inquiryRepository.toQuotationView(row);
    }

    private BidOpeningView toOpeningView(OpeningRow opening) {
        return new BidOpeningView(opening.id(), opening.inquiryId(), opening.openedAt(),
                opening.winnerQuotationId(), inquiryRepository.listOpeningEntries(opening.id()));
    }
}
