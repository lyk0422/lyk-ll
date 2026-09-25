package com.example.starter.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.DispatchService;
import com.example.starter.web.dto.ApiDtos.CloseOrderRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;
import com.example.starter.web.dto.ApiDtos.ReassignRequest;

/**
 * 调度单接口：发电确认、改派、结案、查询。
 */
@RestController
@RequestMapping("/api/v1/dispatch-orders")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /**
     * 查询调度单（在途超时单惰性升级为发电异常）。
     */
    @GetMapping("/{orderId}")
    public DispatchOrderView getOrder(@PathVariable long orderId) {
        return dispatchService.getOrder(orderId);
    }

    /**
     * 油机到位启动后回传发电确认。
     */
    @PostMapping("/{orderId}/confirm-generation")
    public DispatchOrderView confirmGeneration(@PathVariable long orderId,
                                               @Valid @RequestBody ConfirmGenerationRequest request) {
        return dispatchService.confirmGeneration(orderId, request);
    }

    /**
     * 发电异常后改派另一油机。
     */
    @PostMapping("/{orderId}/reassign")
    public DispatchOrderView reassign(@PathVariable long orderId,
                                      @Valid @RequestBody ReassignRequest request) {
        return dispatchService.reassign(orderId, request);
    }

    /**
     * 结案（终态，重复结案幂等成功）。
     */
    @PostMapping("/{orderId}/close")
    public DispatchOrderView close(@PathVariable long orderId,
                                   @Valid @RequestBody CloseOrderRequest request) {
        return dispatchService.close(orderId, request);
    }
}
