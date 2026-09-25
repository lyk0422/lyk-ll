package com.example.starter.dispatch.api;

import com.example.starter.dispatch.api.dto.IssueOrderRequest;
import com.example.starter.dispatch.api.dto.OrderView;
import com.example.starter.dispatch.api.dto.ReceiptRequest;
import com.example.starter.dispatch.service.DispatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 闸门调度令 API：
 * <pre>
 * POST   /api/dispatch/orders              下达调度令（requestId 幂等）
 * POST   /api/dispatch/orders/{id}/accept  闸站签收（幂等）
 * POST   /api/dispatch/orders/{id}/receipts 逐门执行回执（幂等）
 * POST   /api/dispatch/orders/{id}/cancel  签收前撤销（幂等，终态）
 * POST   /api/dispatch/orders/{id}/abort   签收后人工中止（终态，留痕）
 * GET    /api/dispatch/orders/{id}         查询单张令（含全部回执，稳定）
 * GET    /api/dispatch/orders              按序查询全部令
 * </pre>
 */
@RestController
@RequestMapping("/api/dispatch/orders")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping
    public ResponseEntity<OrderView> issue(@Valid @RequestBody IssueOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dispatchService.issue(request));
    }

    @PostMapping("/{requestId}/accept")
    public OrderView accept(@PathVariable String requestId) {
        return dispatchService.accept(requestId);
    }

    @PostMapping("/{requestId}/receipts")
    public OrderView receipt(@PathVariable String requestId,
                             @Valid @RequestBody ReceiptRequest request) {
        return dispatchService.receipt(requestId, request);
    }

    @PostMapping("/{requestId}/cancel")
    public OrderView cancel(@PathVariable String requestId) {
        return dispatchService.cancel(requestId);
    }

    @PostMapping("/{requestId}/abort")
    public OrderView abort(@PathVariable String requestId) {
        return dispatchService.abort(requestId);
    }

    @GetMapping("/{requestId}")
    public OrderView get(@PathVariable String requestId) {
        return dispatchService.get(requestId);
    }

    @GetMapping
    public List<OrderView> list() {
        return dispatchService.list();
    }
}
