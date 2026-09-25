package com.example.starter.irrigation.api;

import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseRequest;
import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseResponse;
import com.example.starter.irrigation.service.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 临时加购水量接口。
 */
@RestController
@RequestMapping("/api/irrigation/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    /** 加购：优先满足欠供斗口，按欠供比例分配。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExtraPurchaseResponse purchase(@Valid @RequestBody ExtraPurchaseRequest request) {
        return purchaseService.purchase(request);
    }
}
