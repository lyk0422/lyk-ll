package com.example.starter.web;

import java.math.BigDecimal;

import com.example.starter.domain.Models.CloseResult;
import com.example.starter.domain.Models.EstimateAdjustResult;
import com.example.starter.service.ClaimService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赔案接口：立案预提、估损调整、最终结案。
 */
@RestController
@RequestMapping("/api")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    /** 立案请求。 */
    public record RegisterClaimRequest(@NotBlank String requestId, @NotBlank String claimNo,
                                       @NotNull BigDecimal estimateAmount) {
    }

    /** 结案请求。 */
    public record CloseClaimRequest(@NotBlank String requestId,
                                    @NotNull BigDecimal finalPaidAmount) {
    }

    /** 估损调整请求。 */
    public record AdjustEstimateRequest(@NotBlank String requestId,
                                        @NotNull BigDecimal newEstimate) {
    }

    @PostMapping("/policies/{policyNo}/claims")
    public ClaimService.RegisterResult register(@PathVariable String policyNo,
                                                @RequestBody @Valid RegisterClaimRequest request) {
        return claimService.register(request.requestId(), policyNo, request.claimNo(),
                request.estimateAmount());
    }

    @PostMapping("/claims/{claimNo}/close")
    public CloseResult close(@PathVariable String claimNo,
                             @RequestBody @Valid CloseClaimRequest request) {
        return claimService.close(request.requestId(), claimNo, request.finalPaidAmount());
    }

    @PostMapping("/claims/{claimNo}/estimate-adjustments")
    public EstimateAdjustResult adjustEstimate(@PathVariable String claimNo,
                                               @RequestBody @Valid AdjustEstimateRequest request) {
        return claimService.adjustEstimate(request.requestId(), claimNo, request.newEstimate());
    }
}
