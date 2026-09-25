package com.example.starter.transfer;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.transfer.TransferDtos.ApplyRequest;
import com.example.starter.transfer.TransferDtos.OrderDetailResponse;
import com.example.starter.transfer.TransferDtos.OrderResponse;
import com.example.starter.transfer.TransferDtos.ReleaseRequest;
import com.example.starter.transfer.TransferDtos.ReleaseResponse;
import com.example.starter.transfer.TransferDtos.StatusChangeResponse;
import com.example.starter.transfer.TransferDtos.VerifyRequest;
import com.example.starter.transfer.TransferDtos.VerifyResponse;

/**
 * 保税转关 REST 入口：申请、核注、撤销、核销、详情查询与海关复核解除异常。
 */
@RestController
@RequestMapping("/api")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /** 申请转关单（requestId 幂等）。 */
    @PostMapping("/transfers")
    public OrderResponse apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request);
    }

    /** 起运地核注：核注与库存冻结扣减同事务。 */
    @PostMapping("/transfers/{orderId}/endorse")
    public StatusChangeResponse endorse(@PathVariable long orderId) {
        return service.endorse(orderId);
    }

    /** 在途前撤销，原子恢复起运仓库存。 */
    @PostMapping("/transfers/{orderId}/cancel")
    public StatusChangeResponse cancel(@PathVariable long orderId) {
        return service.cancel(orderId);
    }

    /** 目的地核销（requestId 幂等，支持分批部分核销）。 */
    @PostMapping("/transfers/{orderId}/verifications")
    public VerifyResponse verify(@PathVariable long orderId,
            @Valid @RequestBody VerifyRequest request) {
        return service.verify(orderId, request);
    }

    /** 单据详情与按序状态留痕。 */
    @GetMapping("/transfers/{orderId}")
    public OrderDetailResponse detail(@PathVariable long orderId) {
        return service.getDetail(orderId);
    }

    /** 海关复核确认解除企业异常。 */
    @PostMapping("/enterprises/{enterpriseId}/exception-release")
    public ReleaseResponse release(@PathVariable String enterpriseId,
            @Valid @RequestBody ReleaseRequest request) {
        return service.releaseEnterprise(enterpriseId, request);
    }
}
