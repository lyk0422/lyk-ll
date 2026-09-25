package com.example.rebate.service;

import com.example.rebate.domain.BatchStatus;
import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.CheckResult;
import com.example.rebate.domain.CustomsDeclaration;
import com.example.rebate.domain.DocType;
import com.example.rebate.domain.InputInvoice;
import com.example.rebate.domain.RateLibrary;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.CustomsDeclarationRepository;
import com.example.rebate.repo.DoubtRepository;
import com.example.rebate.repo.InputInvoiceRepository;
import com.example.rebate.repo.RateLibraryRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.dto.CheckRequest;
import com.example.rebate.web.dto.CheckResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关联校验：为一组报关单创建申报批次，占用单证并逐单执行税率取值与换汇成本校验。
 *
 * <p>整个关联校验在同一事务内：任一报关单已被其它有效批次占用、不存在、缺税率等，
 * 已插入的批次/占用/明细全部回滚。</p>
 */
@Service
public class CheckService {

    static final String DOUBT_RATE_NOT_FOUND = "RATE_NOT_FOUND";
    static final String DOUBT_MATCH_MISSING = "MATCH_MISSING";
    static final String DOUBT_EXCHANGE_COST = "EXCHANGE_COST_OUT_OF_RANGE";

    private final BatchRepository batchRepo;
    private final CustomsDeclarationRepository customsRepo;
    private final InputInvoiceRepository invoiceRepo;
    private final RateLibraryRepository rateRepo;
    private final CheckDetailRepository detailRepo;
    private final DoubtRepository doubtRepo;
    private final TimeService timeService;

    public CheckService(BatchRepository batchRepo,
                        CustomsDeclarationRepository customsRepo,
                        InputInvoiceRepository invoiceRepo,
                        RateLibraryRepository rateRepo,
                        CheckDetailRepository detailRepo,
                        DoubtRepository doubtRepo,
                        TimeService timeService) {
        this.batchRepo = batchRepo;
        this.customsRepo = customsRepo;
        this.invoiceRepo = invoiceRepo;
        this.rateRepo = rateRepo;
        this.detailRepo = detailRepo;
        this.doubtRepo = doubtRepo;
        this.timeService = timeService;
    }

    /**
     * 执行关联校验并返回批次校验视图。
     */
    @Transactional
    public CheckResponse check(CheckRequest req) {
        if (batchRepo.findByNo(req.batchNo()).isPresent()) {
            throw new ConflictException("批次号已存在: " + req.batchNo());
        }
        Set<String> declarationNos = new LinkedHashSet<>(req.declarationNos());

        // 先做存在性与占用预检，占用登记仍以数据库唯一约束为最终并发保障。
        List<CustomsDeclaration> customsList = new ArrayList<>();
        for (String no : declarationNos) {
            CustomsDeclaration customs = customsRepo.findByNo(no)
                    .orElseThrow(() -> new NotFoundException("报关单不存在: " + no));
            Optional<Long> occupied = batchRepo.occupiedBatchOfDeclaration(no);
            if (occupied.isPresent()) {
                throw new ConflictException("报关单 " + no + " 已参与有效批次 batchId=" + occupied.get());
            }
            customsList.add(customs);
        }

        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        long batchId = batchRepo.insert(req.batchNo(), BatchStatus.CHECKED,
                req.declareDate(), zero, timeService.now());

        try {
            for (CustomsDeclaration customs : customsList) {
                occupyAndValidate(batchId, customs, req.declareDate());
            }
        } catch (DuplicateKeyException e) {
            // 并发下另一有效批次抢先占用：整批回滚。
            throw new ConflictException("单证已被其它有效批次占用，关联校验失败");
        }

        BigDecimal totalTax = detailRepo.findByBatchId(batchId).stream()
                .map(CheckDetail::taxBasis)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, java.math.RoundingMode.HALF_UP);
        boolean hasDoubt = !doubtRepo.findUnresolvedByBatchId(batchId).isEmpty();
        batchRepo.updateStatus(batchId,
                hasDoubt ? BatchStatus.DOUBTFUL : BatchStatus.CHECKED, totalTax, timeService.now());

        return BatchViews.check(batchRepo.findById(batchId).orElseThrow(),
                detailRepo.findByBatchId(batchId), doubtRepo.findByBatchId(batchId));
    }

    /**
     * 占用报关单及其发票、关联明细、执行单票校验。
     */
    private void occupyAndValidate(long batchId, CustomsDeclaration customs,
                                   java.time.LocalDate declareDate) {
        if (!batchRepo.registerDeclaration(customs.declarationNo(), batchId)) {
            throw new ConflictException(
                    "报关单 " + customs.declarationNo() + " 已参与其它有效批次");
        }
        batchRepo.linkDocument(batchId, DocType.CUSTOMS, customs.declarationNo(),
                null, customs.productCode(), timeService.now());

        List<InputInvoice> invoices = invoiceRepo.findByDeclarationNo(customs.declarationNo());
        List<InputInvoice> matched = invoices.stream()
                .filter(i -> i.productCode().equals(customs.productCode()))
                .toList();
        for (InputInvoice inv : invoices) {
            if (!batchRepo.registerInvoice(inv.invoiceNo(), batchId)) {
                throw new ConflictException("进项发票 " + inv.invoiceNo() + " 已被其它有效批次占用");
            }
            batchRepo.linkDocument(batchId, DocType.INVOICE, customs.declarationNo(),
                    inv.invoiceNo(), inv.productCode(), timeService.now());
        }

        Optional<RateLibrary> version =
                rateRepo.findEffectiveVersion(customs.productCode(), declareDate);

        BigDecimal taxBasis = null;
        BigDecimal cost = null;
        BigDecimal rate = null;
        Long versionId = null;
        String versionNo = null;
        String doubtType = null;
        CheckResult result = CheckResult.PASS;

        if (matched.isEmpty()) {
            result = CheckResult.DOUBT;
            doubtType = DOUBT_MATCH_MISSING;
        } else if (version.isEmpty()) {
            result = CheckResult.DOUBT;
            doubtType = DOUBT_RATE_NOT_FOUND;
        } else {
            RateLibrary lib = version.get();
            versionId = lib.id();
            versionNo = lib.versionNo();
            rate = lib.rebateRate();
            taxBasis = RebateCalculator.taxBasis(customs.fobAmount(), rate);
            cost = RebateCalculator.exchangeCost(taxBasis, customs.fobUsd());
            if (!RebateCalculator.exchangeCostInRange(cost)) {
                result = CheckResult.DOUBT;
                doubtType = DOUBT_EXCHANGE_COST;
            }
        }

        CheckDetail detail = new CheckDetail(null, batchId, customs.declarationNo(), null,
                customs.productCode(), versionId, versionNo, customs.fobAmount(), customs.fobUsd(),
                rate, taxBasis, cost, result, doubtType);
        long detailId = detailRepo.insert(detail);
        if (result == CheckResult.DOUBT) {
            doubtRepo.insertIfAbsent(batchId, detailId, doubtType, timeService.now());
        }
    }
}
