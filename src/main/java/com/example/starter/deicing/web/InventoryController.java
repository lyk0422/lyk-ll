package com.example.starter.deicing.web;

import com.example.starter.deicing.service.InventoryService;
import com.example.starter.deicing.web.dto.BatchView;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.LedgerResponse;
import com.example.starter.deicing.web.dto.ReturnBorrowRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 除冰液库存与配比管理接口：补料入库、库存台账、跨班组借用与归还。
 * 所有时刻字段使用 UTC（Instant）。
 */
@RestController
@RequestMapping("/api/deicing/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** 补料入库：按批次登记效期，批次号唯一。 */
    @PostMapping("/inbounds")
    public ResponseEntity<BatchView> inbound(@Valid @RequestBody InboundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.inbound(request));
    }

    /** 库存台账：现存量 = 期初 + 入库 - 消耗 - 借出，重复查询稳定。 */
    @GetMapping("/ledger")
    public LedgerResponse ledger() {
        return inventoryService.ledger();
    }

    /** 跨班组借用登记（requestId 幂等；同一液罐同时刻仅一张未归还借用单）。 */
    @PostMapping("/borrows")
    public ResponseEntity<BorrowResponse> borrow(@Valid @RequestBody BorrowRequest request) {
        BorrowResponse response = inventoryService.borrow(request);
        return ResponseEntity.status(response.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /** 借用归还登记（requestId 幂等）。 */
    @PostMapping("/borrows/return")
    public BorrowResponse returnBorrow(@Valid @RequestBody ReturnBorrowRequest request) {
        return inventoryService.returnBorrow(request.requestId());
    }
}
