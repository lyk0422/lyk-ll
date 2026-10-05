package com.example.starter.deicing.web;

import com.example.starter.deicing.service.BorrowService;
import com.example.starter.deicing.service.InventoryService;
import com.example.starter.deicing.service.ManualReviewService;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.BorrowReturnRequest;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.InboundView;
import com.example.starter.deicing.web.dto.LedgerResponse;
import com.example.starter.deicing.web.dto.ManualReviewView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 除冰液库存与配比管理接口：补料入库、跨班组借用/归还、台账与人工确认队列。
 */
@RestController
@RequestMapping("/api/deicing/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final BorrowService borrowService;
    private final ManualReviewService manualReviewService;

    public InventoryController(InventoryService inventoryService,
                               BorrowService borrowService,
                               ManualReviewService manualReviewService) {
        this.inventoryService = inventoryService;
        this.borrowService = borrowService;
        this.manualReviewService = manualReviewService;
    }

    /** 补料入库：按批次登记效期，先到期先出。 */
    @PostMapping("/inbounds")
    public ResponseEntity<InboundView> inbound(@Valid @RequestBody InboundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inventoryService.registerInbound(request));
    }

    /** 跨班组借用（requestId 幂等，同键异参 409；同液罐仅一张未归还借用单）。 */
    @PostMapping("/borrows")
    public ResponseEntity<BorrowResponse> borrow(@Valid @RequestBody BorrowRequest request) {
        BorrowResponse response = borrowService.borrow(request);
        return ResponseEntity.status(response.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /** 借用归还登记（幂等）。 */
    @PostMapping("/borrow-returns")
    public BorrowResponse returnBorrow(@Valid @RequestBody BorrowReturnRequest request) {
        return borrowService.returnBorrow(request.requestId());
    }

    /** 库存台账：期初 + 入库 - 消耗 - 借出 = 现存，重复查询稳定。 */
    @GetMapping("/ledger")
    public LedgerResponse ledger() {
        return inventoryService.ledger();
    }

    /** 人工确认队列：库存不足转入的两步法申请。 */
    @GetMapping("/manual-reviews")
    public List<ManualReviewView> manualReviews() {
        return manualReviewService.list();
    }
}
