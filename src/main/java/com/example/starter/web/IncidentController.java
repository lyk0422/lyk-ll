package com.example.starter.web;

import com.example.starter.service.IncidentService;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteConfirmationResponse;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
import com.example.starter.web.dto.IncidentEvidence;
import com.example.starter.web.dto.IncidentReportRequest;
import com.example.starter.web.dto.IncidentResponse;
import com.example.starter.web.dto.ResumeRequest;
import com.example.starter.web.dto.ResumeResponse;
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

/**
 * 途中异常停车与续行许可 API（仅模拟软件状态，不控制列车）。
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    /**
     * 登记途中异常：冻结编组/列尾绑定/原发车证据，进入续行阻断；同作业仅一条活动处置。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponse report(@PathVariable long jobId,
                                   @Valid @RequestBody IncidentReportRequest request) {
        return service.report(jobId, request);
    }

    /**
     * 途中制动试验：绑定处置单与当前编组版本，复用始发试验数值规则。
     */
    @PostMapping("/{incidentId}/en-route-tests")
    @ResponseStatus(HttpStatus.CREATED)
    public EnRouteTestResponse enRouteTest(@PathVariable long jobId, @PathVariable long incidentId,
                                           @Valid @RequestBody EnRouteTestRequest request) {
        return service.enRouteTest(jobId, incidentId, request);
    }

    /**
     * 两名不同人员共同确认途中新试验与列尾一致性。
     */
    @PostMapping("/{incidentId}/en-route-confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public EnRouteConfirmationResponse confirmEnRoute(@PathVariable long jobId,
                                                      @PathVariable long incidentId,
                                                      @Valid @RequestBody EnRouteConfirmRequest request) {
        return service.confirmEnRoute(jobId, incidentId, request);
    }

    /**
     * 续行许可：同事务重查编组、自检有效期及最新试验版本；仅结束本次阻断。
     */
    @PostMapping("/{incidentId}/resumes")
    public ResumeResponse resume(@PathVariable long jobId, @PathVariable long incidentId,
                                 @Valid @RequestBody ResumeRequest request) {
        return service.resume(jobId, incidentId, request);
    }

    /**
     * 查询作业全部处置证据（处置单、途中试验与双人确认历史）。
     */
    @GetMapping
    public List<IncidentEvidence> getEvidence(@PathVariable long jobId) {
        return service.getEvidence(jobId);
    }
}
