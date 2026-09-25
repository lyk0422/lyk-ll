package com.example.starter.transition.web;

import com.example.starter.transition.dto.ApplyOrderRequest;
import com.example.starter.transition.dto.DisposeRollsRequest;
import com.example.starter.transition.dto.ExecuteOrderRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.dto.InspectionRequest;
import com.example.starter.transition.dto.OrderView;
import com.example.starter.transition.dto.ReexamRequest;
import com.example.starter.transition.dto.RollView;
import com.example.starter.transition.dto.TransitionRecomputeView;
import com.example.starter.transition.service.TransitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 改品种过渡料控制 REST 接口。
 */
@RestController
@RequestMapping("/api/transitions")
public class TransitionController {

    private final TransitionService service;

    public TransitionController(TransitionService service) {
        this.service = service;
    }

    @PostMapping("/orders")
    public ResponseEntity<IdempotentResult<OrderView>> apply(@Valid @RequestBody ApplyOrderRequest request) {
        IdempotentResult<OrderView> result = service.apply(request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @PostMapping("/orders/{orderNo}/decide")
    public OrderView decide(@PathVariable String orderNo) {
        return service.decide(orderNo);
    }

    @PostMapping("/orders/{orderNo}/execute")
    public OrderView execute(@PathVariable String orderNo,
                             @Valid @RequestBody ExecuteOrderRequest request) {
        return service.execute(orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/dispose")
    public OrderView dispose(@PathVariable String orderNo,
                             @Valid @RequestBody DisposeRollsRequest request) {
        return service.dispose(orderNo, request);
    }

    @PostMapping("/orders/{orderNo}/complete")
    public OrderView complete(@PathVariable String orderNo) {
        return service.complete(orderNo);
    }

    @PostMapping("/orders/{orderNo}/archive")
    public OrderView archive(@PathVariable String orderNo) {
        return service.archive(orderNo);
    }

    @GetMapping("/orders/{orderNo}")
    public OrderView getOrder(@PathVariable String orderNo) {
        return service.getOrder(orderNo);
    }

    @PostMapping("/inspections")
    public IdempotentResult<RollView> submitInspection(@Valid @RequestBody InspectionRequest request) {
        return service.submitInspection(request);
    }

    @PostMapping("/reexams")
    public IdempotentResult<RollView> submitReexam(@Valid @RequestBody ReexamRequest request) {
        return service.submitReexam(request);
    }

    @GetMapping("/rolls/{rollNo}")
    public RollView getRoll(@PathVariable String rollNo) {
        return service.getRoll(rollNo);
    }

    @GetMapping("/orders/{orderNo}/recompute")
    public TransitionRecomputeView recompute(@PathVariable String orderNo) {
        return service.recompute(orderNo);
    }
}
