package com.example.starter.invoice;

import com.example.starter.invoice.InvoiceService.ActionResponse;
import com.example.starter.invoice.InvoiceService.InvoiceView;
import com.example.starter.invoice.InvoiceService.IssueBlueInvoiceRequest;
import com.example.starter.invoice.InvoiceService.IssueResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发票接口：蓝票开具、交付、归档与详情查询。
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/blue")
    public IssueResponse issueBlue(@RequestBody IssueBlueInvoiceRequest request) {
        return invoiceService.issueBlue(request);
    }

    @PostMapping("/{invoiceNo}/deliver")
    public ActionResponse deliver(@PathVariable String invoiceNo) {
        return invoiceService.deliver(invoiceNo);
    }

    @PostMapping("/{invoiceNo}/archive")
    public ActionResponse archive(@PathVariable String invoiceNo) {
        return invoiceService.archive(invoiceNo);
    }

    @GetMapping("/{invoiceNo}")
    public InvoiceView getInvoice(@PathVariable String invoiceNo) {
        return invoiceService.getInvoice(invoiceNo);
    }
}
