package com.example.starter.transition.web;

import com.example.starter.transition.dto.BlendAdjustRequest;
import com.example.starter.transition.dto.BlendControlStateView;
import com.example.starter.transition.dto.BlendReadingRequest;
import com.example.starter.transition.dto.BlendReadingView;
import com.example.starter.transition.dto.BlendRatioVersionView;
import com.example.starter.transition.dto.BlendRecomputeView;
import com.example.starter.transition.dto.BlendResumeRequest;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.service.BlendService;
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
 * 回抄浆掺配比例控制 REST 接口：执行中调整、读数上报、失控恢复与版本链复算。
 */
@RestController
@RequestMapping("/api/transitions/orders/{orderNo}")
public class BlendController {

    private final BlendService service;

    public BlendController(BlendService service) {
        this.service = service;
    }

    /** 执行中掺配比例调整：判定结论与新版本同事务落库。 */
    @PostMapping("/blend-ratios")
    public ResponseEntity<IdempotentResult<BlendRatioVersionView>> adjustRatio(
            @PathVariable String orderNo,
            @Valid @RequestBody BlendAdjustRequest request) {
        IdempotentResult<BlendRatioVersionView> result = service.adjustRatio(orderNo, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    /** 实际掺配读数上报：按订单与时刻去重，连续超差判失控。 */
    @PostMapping("/blend-readings")
    public ResponseEntity<IdempotentResult<BlendReadingView>> submitReading(
            @PathVariable String orderNo,
            @Valid @RequestBody BlendReadingRequest request) {
        IdempotentResult<BlendReadingView> result = service.submitReading(orderNo, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    /** 失控恢复：须复核确认。 */
    @PostMapping("/blend-control/resume")
    public IdempotentResult<BlendControlStateView> resume(
            @PathVariable String orderNo,
            @Valid @RequestBody BlendResumeRequest request) {
        return service.resume(orderNo, request);
    }

    @GetMapping("/blend-control")
    public BlendControlStateView getControlState(@PathVariable String orderNo) {
        return service.getControlState(orderNo);
    }

    /** 比例版本链复算：按版本复算实际掺配轨迹。 */
    @GetMapping("/blend-recompute")
    public BlendRecomputeView recompute(@PathVariable String orderNo) {
        return service.recompute(orderNo);
    }
}
