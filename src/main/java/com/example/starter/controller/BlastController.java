package com.example.starter.controller;

import com.example.starter.dto.EvaluationRequest;
import com.example.starter.dto.EvaluationResponse;
import com.example.starter.dto.FilingRequest;
import com.example.starter.dto.FilingResponse;
import com.example.starter.dto.PermitRequest;
import com.example.starter.dto.PermitResponse;
import com.example.starter.dto.ReadingRequest;
import com.example.starter.dto.ReadingResponse;
import com.example.starter.dto.ReviewRequest;
import com.example.starter.dto.ReviewResponse;
import com.example.starter.service.EvaluationService;
import com.example.starter.service.FilingService;
import com.example.starter.service.FreezeService;
import com.example.starter.service.PermitService;
import com.example.starter.service.VibrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爆破作业审批与振动监测联动 API。
 */
@RestController
@RequestMapping("/api")
public class BlastController {

    private final PermitService permits;
    private final FilingService filings;
    private final VibrationService vibrations;
    private final FreezeService freezes;
    private final EvaluationService evaluations;

    public BlastController(PermitService permits, FilingService filings,
            VibrationService vibrations, FreezeService freezes,
            EvaluationService evaluations) {
        this.permits = permits;
        this.filings = filings;
        this.vibrations = vibrations;
        this.freezes = freezes;
        this.evaluations = evaluations;
    }

    /**
     * 提交爆破作业申请（幂等）。
     */
    @PostMapping("/permits")
    @ResponseStatus(HttpStatus.OK)
    public PermitResponse apply(@Valid @RequestBody PermitRequest request) {
        return permits.apply(request);
    }

    /**
     * 查询申请单。
     */
    @GetMapping("/permits/{permitId}")
    public PermitResponse getPermit(@PathVariable long permitId) {
        return permits.get(permitId);
    }

    /**
     * 审批通过申请。
     */
    @PostMapping("/permits/{permitId}/approve")
    public PermitResponse approve(@PathVariable long permitId) {
        return permits.approve(permitId);
    }

    /**
     * 起爆备案（幂等）。
     */
    @PostMapping("/filings")
    public FilingResponse file(@Valid @RequestBody FilingRequest request) {
        return filings.file(request);
    }

    /**
     * 振动监测回传。
     */
    @PostMapping("/vibration-readings")
    public ReadingResponse submitReading(@Valid @RequestBody ReadingRequest request) {
        return vibrations.submit(request);
    }

    /**
     * 监管复核解除冻结（幂等）。
     */
    @PostMapping("/freeze-reviews")
    public ReviewResponse unfreeze(@Valid @RequestBody ReviewRequest request) {
        return freezes.unfreeze(request);
    }

    /**
     * 提交效果评估并封档（终态，重复封档幂等成功）。
     */
    @PostMapping("/permits/{permitId}/evaluations")
    public EvaluationResponse seal(@PathVariable long permitId,
            @Valid @RequestBody EvaluationRequest request) {
        return evaluations.seal(permitId, request);
    }
}
