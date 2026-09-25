package com.example.starter.dispatch.api;

import com.example.starter.dispatch.service.DispatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 闸门调度令 HTTP 接口（骨架）。
 */
@RestController
@RequestMapping("/api/dispatch/orders")
public class DispatchController {

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView issue(@Valid @RequestBody IssueOrderRequest request) {
        return service.issue(request);
    }

    @PostMapping("/{requestId}/acknowledgements")
    public OrderView acknowledge(@PathVariable String requestId) {
        return service.acknowledge(requestId);
    }

    @PostMapping("/{requestId}/receipts")
    public OrderView receipt(@PathVariable String requestId,
                             @Valid @RequestBody ReceiptRequest request) {
        return service.receipt(requestId, request);
    }

    @PostMapping("/{requestId}/cancellations")
    public OrderView cancel(@PathVariable String requestId) {
        return service.cancel(requestId);
    }

    @PostMapping("/{requestId}/aborts")
    public OrderView abort(@PathVariable String requestId,
                           @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return service.abort(requestId, note);
    }

    @PostMapping("/{requestId}/takeover")
    public OrderView takeOver(@PathVariable String requestId) {
        return service.takeOver(requestId);
    }

    @GetMapping
    public List<OrderView> list() {
        return service.listOrders();
    }

    @GetMapping("/{requestId}")
    public OrderView get(@PathVariable String requestId) {
        return service.getOrder(requestId);
    }

    @GetMapping("/{requestId}/notifications")
    public List<NotificationView> notifications(@PathVariable String requestId) {
        return service.listNotifications(requestId);
    }
}
