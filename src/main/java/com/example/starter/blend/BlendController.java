package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.CreateOrderRequest;
import com.example.starter.blend.BlendDtos.OrderDetail;
import com.example.starter.blend.BlendDtos.OrderResponse;
import com.example.starter.blend.BlendDtos.ReblendRequest;
import com.example.starter.blend.BlendDtos.ReblendResponse;
import com.example.starter.blend.BlendDtos.ReleaseRequest;
import com.example.starter.blend.BlendDtos.ReleaseResponse;
import com.example.starter.blend.BlendDtos.SampleRequest;
import com.example.starter.blend.BlendDtos.SampleResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 炼油调合配方与质量卡边控制接口。
 */
@RestController
@RequestMapping("/api/blend-orders")
public class BlendController {

    private final BlendService service;

    public BlendController(BlendService service) {
        this.service = service;
    }

    /**
     * 配方下达（含组分切罐与库存扣减，同一事务）。
     */
    @PostMapping
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return service.createOrder(request);
    }

    /**
     * 化验提交（采样点1/2）。
     */
    @PostMapping("/{orderId}/samples")
    public SampleResponse submitSample(@PathVariable long orderId,
                                       @Valid @RequestBody SampleRequest request) {
        return service.submitSample(orderId, request);
    }

    /**
     * 返调（仅首样不合格后一次）。
     */
    @PostMapping("/{orderId}/reblend")
    public ReblendResponse reblend(@PathVariable long orderId,
                                   @Valid @RequestBody ReblendRequest request) {
        return service.reblend(orderId, request);
    }

    /**
     * 放行封账（终态，重复封账幂等成功）。
     */
    @PostMapping("/{orderId}/release")
    public ReleaseResponse release(@PathVariable long orderId,
                                   @Valid @RequestBody ReleaseRequest request) {
        return service.release(orderId, request);
    }

    /**
     * 调合单详情（指标、卡边率、批次链复算）。
     */
    @GetMapping("/{orderId}")
    public OrderDetail detail(@PathVariable long orderId) {
        return service.getOrder(orderId);
    }
}
