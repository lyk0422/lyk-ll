package com.example.starter.blend;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.blend.BlendContracts.CreateOrderRequest;
import com.example.starter.blend.BlendContracts.LabSampleRequest;
import com.example.starter.blend.BlendContracts.LabSampleResponse;
import com.example.starter.blend.BlendContracts.OrderDetailResponse;
import com.example.starter.blend.BlendContracts.OrderResponse;
import com.example.starter.blend.BlendContracts.ReblendRequest;
import com.example.starter.blend.BlendContracts.ReblendResponse;
import com.example.starter.blend.BlendContracts.ReleaseResponse;

/**
 * 调合服务 REST 入口：配方下达 → 组分切罐 → 在线调和 → 双采样化验判定 → 放行/返调。
 */
@RestController
@RequestMapping("/api/blend-orders")
public class BlendController {

    private final BlendOrderService service;

    public BlendController(BlendOrderService service) {
        this.service = service;
    }

    /** 配方下达：幂等；校验罐余量，切罐与扣量原子完成。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return service.createOrder(request);
    }

    /** 化验提交：幂等；双采样点均合格方可放行，封账后数据只记迟到事件。 */
    @PostMapping("/{orderId}/lab-samples")
    @ResponseStatus(HttpStatus.CREATED)
    public LabSampleResponse submitSample(@PathVariable long orderId,
                                          @Valid @RequestBody LabSampleRequest request) {
        return service.submitLabSample(orderId, request);
    }

    /** 返调：仅首样不合格允许一次，生成引用原批次的新批次。 */
    @PostMapping("/{orderId}/reblend")
    @ResponseStatus(HttpStatus.CREATED)
    public ReblendResponse reblend(@PathVariable long orderId,
                                   @Valid @RequestBody ReblendRequest request) {
        return service.reblend(orderId, request);
    }

    /** 放行封账：终态，重复封账返回幂等成功。 */
    @PostMapping("/{orderId}/release")
    public ReleaseResponse release(@PathVariable long orderId) {
        return service.release(orderId);
    }

    /** 调合单详情：指标、卡边率与批次链可复算，重复查询结果稳定。 */
    @GetMapping("/{orderId}")
    public OrderDetailResponse detail(@PathVariable long orderId) {
        return service.getDetail(orderId);
    }
}
