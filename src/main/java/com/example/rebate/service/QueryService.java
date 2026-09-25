package com.example.rebate.service;

import com.example.rebate.domain.CheckDetail;
import com.example.rebate.domain.DeclarationBatch;
import com.example.rebate.domain.InputInvoice;
import com.example.rebate.domain.RateLibrary;
import com.example.rebate.domain.TreasuryWriteoff;
import com.example.rebate.repo.BatchRepository;
import com.example.rebate.repo.CheckDetailRepository;
import com.example.rebate.repo.DoubtRepository;
import com.example.rebate.repo.InputInvoiceRepository;
import com.example.rebate.repo.RateLibraryRepository;
import com.example.rebate.repo.TreasuryWriteoffRepository;
import com.example.rebate.web.NotFoundException;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.CheckResponse;
import com.example.rebate.web.dto.RecalcResponse;
import com.example.rebate.web.dto.WriteoffView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次查询与复算：文库版本取值、校验明细、核销差异均可按落库输入重新计算并比对。
 */
@Service
public class QueryService {

    private final BatchRepository batchRepo;
    private final CheckDetailRepository detailRepo;
    private final DoubtRepository doubtRepo;
    private final TreasuryWriteoffRepository writeoffRepo;
    private final RateLibraryRepository rateRepo;
    private final InputInvoiceRepository invoiceRepo;

    public QueryService(BatchRepository batchRepo,
                        CheckDetailRepository detailRepo,
                        DoubtRepository doubtRepo,
                        TreasuryWriteoffRepository writeoffRepo,
                        RateLibraryRepository rateRepo,
                        InputInvoiceRepository invoiceRepo) {
        this.batchRepo = batchRepo;
        this.detailRepo = detailRepo;
        this.doubtRepo = doubtRepo;
        this.writeoffRepo = writeoffRepo;
        this.rateRepo = rateRepo;
        this.invoiceRepo = invoiceRepo;
    }

    /**
     * 批次状态视图。
     */
    @Transactional(readOnly = true)
    public BatchView getBatch(String batchNo) {
        return BatchViews.batch(load(batchNo));
    }

    /**
     * 批次校验明细与疑点视图。
     */
    @Transactional(readOnly = true)
    public CheckResponse getCheck(String batchNo) {
        DeclarationBatch batch = load(batchNo);
        return BatchViews.check(batch,
                detailRepo.findByBatchId(batch.id()), doubtRepo.findByBatchId(batch.id()));
    }

    /**
     * 复算校验明细：按批次申报日（UTC）重新取有效文库版本，重算计税依据与换汇成本，
     * 并与首次落库值逐项比对（含版本号、税率、结果）。
     */
    @Transactional(readOnly = true)
    public RecalcResponse recalcCheck(String batchNo) {
        DeclarationBatch batch = load(batchNo);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean consistent = true;
        for (CheckDetail d : detailRepo.findByBatchId(batch.id())) {
            RateLibrary resolved = rateRepo
                    .findEffectiveVersion(d.productCode(), batch.declareDate()).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("detailId", d.id());
            item.put("declarationNo", d.declarationNo());
            item.put("productCode", d.productCode());

            boolean itemOk = true;
            long matchedInvoices = invoiceRepo.findByDeclarationNo(d.declarationNo()).stream()
                    .filter(i -> i.productCode().equals(d.productCode()))
                    .count();
            if (matchedInvoices == 0) {
                itemOk = "MATCH_MISSING".equals(d.doubtType()) && d.versionNo() == null;
                item.put("recalcMatchedInvoices", 0);
            } else if (resolved == null) {
                item.put("recalcVersionNo", null);
                itemOk = d.versionNo() == null && "RATE_NOT_FOUND".equals(d.doubtType());
            } else {
                BigDecimal recalcBasis =
                        RebateCalculator.taxBasis(d.fobAmount(), resolved.rebateRate());
                BigDecimal recalcCost =
                        RebateCalculator.exchangeCost(recalcBasis, d.fobUsd());
                boolean inRange = RebateCalculator.exchangeCostInRange(recalcCost);
                item.put("recalcVersionNo", resolved.versionNo());
                item.put("recalcRebateRate", resolved.rebateRate());
                item.put("recalcTaxBasis", recalcBasis);
                item.put("recalcExchangeCost", recalcCost);
                item.put("storedVersionNo", d.versionNo());
                item.put("storedTaxBasis", d.taxBasis());
                item.put("storedExchangeCost", d.exchangeCost());
                itemOk = resolved.versionNo().equals(d.versionNo())
                        && eq(recalcBasis, d.taxBasis())
                        && eq(recalcCost, d.exchangeCost())
                        && inRange == (d.result() == com.example.rebate.domain.CheckResult.PASS);
                if (!inRange) {
                    itemOk = itemOk && "EXCHANGE_COST_OUT_OF_RANGE".equals(d.doubtType());
                }
            }
            item.put("match", itemOk);
            consistent &= itemOk;
            items.add(item);
        }
        return new RecalcResponse("CHECK_DETAIL:" + batchNo, consistent, items);
    }

    /**
     * 复算核销差异：以落库申报税额与到账金额重算差额、比例与一致性，逐条比对。
     */
    @Transactional(readOnly = true)
    public RecalcResponse recalcWriteoffs(String batchNo) {
        DeclarationBatch batch = load(batchNo);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean consistent = true;
        for (TreasuryWriteoff w : writeoffRepo.findByBatchId(batch.id())) {
            BigDecimal recalcDiff =
                    RebateCalculator.diffAmount(w.declaredTax(), w.receivedAmount());
            BigDecimal recalcRatio =
                    RebateCalculator.diffRatio(w.declaredTax(), w.receivedAmount());
            boolean recalcConsistent = RebateCalculator.diffConsistent(recalcRatio);
            boolean itemOk = eq(recalcDiff, w.diffAmount())
                    && eq(recalcRatio, w.diffRatio())
                    && recalcConsistent == w.consistent()
                    && w.manualFlag() == !recalcConsistent;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("writeoffId", w.id());
            item.put("treasurySerial", w.treasurySerial());
            item.put("recalcDiffAmount", recalcDiff);
            item.put("storedDiffAmount", w.diffAmount());
            item.put("recalcDiffRatio", recalcRatio);
            item.put("storedDiffRatio", w.diffRatio());
            item.put("recalcConsistent", recalcConsistent);
            item.put("storedConsistent", w.consistent());
            item.put("match", itemOk);
            consistent &= itemOk;
            items.add(item);
        }
        return new RecalcResponse("TREASURY_WRITEOFF:" + batchNo, consistent, items);
    }

    /**
     * 复算文库版本取值：给定商品编码与 UTC 申报日，返回命中的有效版本。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> recalcLibrary(String productCode, LocalDate declareDate) {
        RateLibrary lib = rateRepo.findEffectiveVersion(productCode, declareDate).orElse(null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productCode", productCode);
        item.put("declareDate", declareDate.toString());
        if (lib == null) {
            item.put("effectiveVersion", null);
        } else {
            item.put("effectiveVersion", lib.versionNo());
            item.put("effectiveFrom", lib.effectiveFrom().toString());
            item.put("rebateRate", lib.rebateRate());
        }
        return item;
    }

    /**
     * 核销登记列表。
     */
    @Transactional(readOnly = true)
    public List<WriteoffView> listWriteoffs(String batchNo) {
        DeclarationBatch batch = load(batchNo);
        return writeoffRepo.findByBatchId(batch.id()).stream()
                .map(w -> new WriteoffView(w.id(), batch.batchNo(), w.treasurySerial(),
                        w.declaredTax(), w.receivedAmount(), w.diffAmount(), w.diffRatio(),
                        w.consistent(), w.manualFlag(), batch.status().name(), w.registeredAt()))
                .toList();
    }

    private DeclarationBatch load(String batchNo) {
        return batchRepo.findByNo(batchNo)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + batchNo));
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
}
