package com.example.rebate.service;

import com.example.rebate.domain.CustomsDeclaration;
import com.example.rebate.domain.InputInvoice;
import com.example.rebate.domain.RateLibrary;
import com.example.rebate.repo.CustomsDeclarationRepository;
import com.example.rebate.repo.InputInvoiceRepository;
import com.example.rebate.repo.RateLibraryRepository;
import com.example.rebate.support.TimeService;
import com.example.rebate.web.ConflictException;
import com.example.rebate.web.dto.CollectCustomsRequest;
import com.example.rebate.web.dto.CollectInvoiceRequest;
import com.example.rebate.web.dto.CollectResponse;
import com.example.rebate.web.dto.LibraryRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据采集：退税率文库版本、报关单、进项发票（均为合成数据）。
 *
 * <p>采集去重：报关单号唯一；发票按“报关单号＋发票号”唯一。重复采集为幂等成功，
 * 返回 {@code created=false}，不覆盖既有数据。</p>
 */
@Service
public class CollectionService {

    private final CustomsDeclarationRepository customsRepo;
    private final InputInvoiceRepository invoiceRepo;
    private final RateLibraryRepository rateRepo;
    private final TimeService timeService;

    public CollectionService(CustomsDeclarationRepository customsRepo,
                             InputInvoiceRepository invoiceRepo,
                             RateLibraryRepository rateRepo,
                             TimeService timeService) {
        this.customsRepo = customsRepo;
        this.invoiceRepo = invoiceRepo;
        this.rateRepo = rateRepo;
        this.timeService = timeService;
    }

    /**
     * 登记退税率文库版本。同商品编码+版本号重复登记冲突。
     */
    @Transactional
    public CollectResponse addLibrary(LibraryRequest req) {
        RateLibrary lib = new RateLibrary(null, req.productCode(), req.versionNo(),
                req.effectiveFrom(), req.rebateRate());
        try {
            rateRepo.insert(lib);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(
                    "文库版本已存在: " + req.productCode() + "/" + req.versionNo());
        }
        return new CollectResponse(true, req.productCode() + "/" + req.versionNo());
    }

    /**
     * 采集报关单；同报关单号重复采集幂等返回。
     */
    @Transactional
    public CollectResponse collectCustoms(CollectCustomsRequest req) {
        CustomsDeclaration d = new CustomsDeclaration(null, req.declarationNo(), req.productCode(),
                req.fobAmount(), req.fobUsd(), timeService.now());
        boolean created = customsRepo.insertIfAbsent(d);
        return new CollectResponse(created, req.declarationNo());
    }

    /**
     * 采集进项发票；按“报关单号＋发票号”去重。
     */
    @Transactional
    public CollectResponse collectInvoice(CollectInvoiceRequest req) {
        InputInvoice inv = new InputInvoice(null, req.invoiceNo(), req.declarationNo(),
                req.productCode(), req.amount(), timeService.now());
        boolean created = invoiceRepo.insertIfAbsent(inv);
        return new CollectResponse(created, req.declarationNo() + "+" + req.invoiceNo());
    }
}
