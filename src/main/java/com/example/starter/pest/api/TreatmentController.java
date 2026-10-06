package com.example.starter.pest.api;

import com.example.starter.pest.TreatmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处置链接口：投放、虫迹、解除申请与双方确认。
 */
@RestController
@RequestMapping("/api/chains")
public class TreatmentController {

    private final TreatmentService treatmentService;

    public TreatmentController(TreatmentService treatmentService) {
        this.treatmentService = treatmentService;
    }

    /**
     * 查询处置链状态。
     */
    @GetMapping("/{chainId}")
    public Results.ChainView getChain(@PathVariable long chainId) {
        return treatmentService.getChain(chainId);
    }

    /**
     * 治理投放（双人登记，与库存扣减同事务）。
     */
    @PostMapping("/{chainId}/dispenses")
    public Results.DispenseResult dispense(@PathVariable long chainId,
                                           @Valid @RequestBody Requests.DispenseRequest request) {
        return treatmentService.dispense(chainId, request);
    }

    /**
     * 监测期登记新增虫迹。
     */
    @PostMapping("/{chainId}/traces")
    public Results.TraceResult registerTrace(@PathVariable long chainId,
                                             @Valid @RequestBody Requests.TraceRequest request) {
        return treatmentService.registerTrace(chainId, request);
    }

    /**
     * 申请解除（恰满 14 天无新增虫迹）。
     */
    @PostMapping("/{chainId}/lift-requests")
    public Results.LiftApplyResult applyLift(@PathVariable long chainId,
                                             @Valid @RequestBody Requests.LiftApplyRequest request) {
        return treatmentService.applyLift(chainId, request);
    }

    /**
     * 解除确认（消杀与库房双方确认）。
     */
    @PostMapping("/{chainId}/lift-confirms")
    public Results.LiftConfirmResult confirmLift(@PathVariable long chainId,
                                                 @Valid @RequestBody Requests.LiftConfirmRequest request) {
        return treatmentService.confirmLift(chainId, request);
    }
}
