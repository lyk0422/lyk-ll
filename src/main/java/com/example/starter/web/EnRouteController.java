package com.example.starter.web;

import com.example.starter.service.EnRouteService;
import com.example.starter.web.dto.EnRouteAbnormalityRequest;
import com.example.starter.web.dto.EnRouteConfirmationResponse;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteDispositionEvidence;
import com.example.starter.web.dto.EnRouteDispositionResponse;
import com.example.starter.web.dto.EnRouteResumeRequest;
import com.example.starter.web.dto.EnRouteResumeResponse;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
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
 * 途中异常停车与续行许可 API（仅模拟软件状态，不控制列车）。
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/en-route-dispositions")
public class EnRouteController {

    private final EnRouteService service;

    public EnRouteController(EnRouteService service) {
        this.service = service;
    }

    /**
     * 登记途中异常：冻结编组/列尾绑定/原发车证据，进入续行阻断；同作业仅一条活动处置。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnRouteDispositionResponse reportAbnormality(@PathVariable long jobId,
                                                        @Valid @RequestBody EnRouteAbnormalityRequest request) {
        return service.reportAbnormality(jobId, request);
    }

    /**
     * 记录途中试验：绑定处置单与当前编组版本的新压力曲线。
     */
    @PostMapping("/{dispositionId}/tests")
    @ResponseStatus(HttpStatus.CREATED)
    public EnRouteTestResponse recordTest(@PathVariable long jobId, @PathVariable long dispositionId,
                                          @Valid @RequestBody EnRouteTestRequest request) {
        return service.recordTest(jobId, dispositionId, request);
    }

    /**
     * 两名不同人员确认新试验与列尾一致性。
     */
    @PostMapping("/{dispositionId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public EnRouteConfirmationResponse confirm(@PathVariable long jobId, @PathVariable long dispositionId,
                                               @Valid @RequestBody EnRouteConfirmRequest request) {
        return service.confirm(jobId, dispositionId, request);
    }

    /**
     * 续行许可：同事务重查编组、自检有效期及最新试验版本；成功仅结束本次阻断。
     */
    @PostMapping("/{dispositionId}/resume")
    public EnRouteResumeResponse resume(@PathVariable long jobId, @PathVariable long dispositionId,
                                        @Valid @RequestBody EnRouteResumeRequest request) {
        return service.resume(jobId, dispositionId, request);
    }

    /**
     * 查询处置单完整证据（途中试验与双人确认历史）。
     */
    @GetMapping("/{dispositionId}")
    public EnRouteDispositionEvidence getDispositionEvidence(@PathVariable long jobId,
                                                             @PathVariable long dispositionId) {
        return service.getDispositionEvidence(jobId, dispositionId);
    }

    /**
     * 查询作业当前活动处置单证据；无活动处置返回 404。
     */
    @GetMapping("/active")
    public EnRouteDispositionEvidence getActiveDispositionEvidence(@PathVariable long jobId) {
        return service.getActiveDispositionEvidence(jobId);
    }
}
