package com.example.starter.invoice.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.invoice.service.InvoiceService;

/**
 * 发票接口：开具、交付、归档与查询。
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    /**
     * 开具蓝字发票（requestId 幂等，额度串行校验）。
     */
    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse issue(@Valid @RequestBody IssueInvoiceRequest request) {
        return invoiceService.issue(request);
    }

    /**
     * 交付发票。
     */
    @PostMapping("/{invoiceNo}/deliver")
    public InvoiceResponse deliver(@PathVariable String invoiceNo) {
        return invoiceService.deliver(invoiceNo);
    }

    /**
     * 归档发票（终态，重复归档幂等成功）。
     */
    @PostMapping("/{invoiceNo}/archive")
    public InvoiceResponse archive(@PathVariable String invoiceNo) {
        return invoiceService.archive(invoiceNo);
    }

    /**
     * 查询发票（蓝票含红冲链，红票含原票号码）。
     */
    @GetMapping("/{invoiceNo}")
    public InvoiceResponse get(@PathVariable String invoiceNo) {
        return invoiceService.getByInvoiceNo(invoiceNo);
    }
}
