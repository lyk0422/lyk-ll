package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementResponse;
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
 * 联检接口：联检单创建、分样、结果/复测、综合判定、补充信息与查询（合成规则）。
 */
@RestController
@RequestMapping("/api")
public class JointController {

    private final JointService jointService;

    public JointController(JointService jointService) {
        this.jointService = jointService;
    }

    /**
     * 建立联检单（requestId 幂等）。
     */
    @PostMapping("/joint-orders")
    public ResponseEntity<JointOrderResponse> create(@Valid @RequestBody JointOrderCreateRequest request) {
        JointOrderResponse response = jointService.createOrder(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 分样分配：覆盖全部项目，任一失败整单不分样。
     */
    @PostMapping("/joint-orders/{orderId}/assignments")
    public JointAssignResponse assign(@PathVariable long orderId,
                                      @Valid @RequestBody JointAssignRequest request) {
        return jointService.assign(orderId, request);
    }

    /**
     * 提交/复测联检结果：恰等限值合格，复测保留旧结果递增版本。
     */
    @PostMapping("/joint-orders/{orderId}/results")
    public JointResultResponse submitResult(@PathVariable long orderId,
                                            @Valid @RequestBody JointResultRequest request) {
        return jointService.submitResult(orderId, request);
    }

    /**
     * 综合判定：携带全部项版本并冻结证据，合格放行、不合格转处置留样。
     */
    @PostMapping("/joint-orders/{orderId}/judgment")
    public JointJudgeResponse judge(@PathVariable long orderId,
                                    @Valid @RequestBody JointJudgeRequest request) {
        return jointService.judge(orderId, request);
    }

    /**
     * 判定后补充信息：只记录不重开。
     */
    @PostMapping("/joint-orders/{orderId}/supplements")
    public JointSupplementResponse supplement(@PathVariable long orderId,
                                              @Valid @RequestBody JointSupplementRequest request) {
        return jointService.supplement(orderId, request);
    }

    /**
     * 联检单详情。
     */
    @GetMapping("/joint-orders/{orderId}")
    public JointOrderDetailResponse detail(@PathVariable long orderId) {
        return jointService.detail(orderId);
    }

    /**
     * 按批次查询联检单详情。
     */
    @GetMapping("/batches/{batchId}/joint-order")
    public JointOrderDetailResponse detailByBatch(@PathVariable long batchId) {
        return jointService.detailByBatch(batchId);
    }
}
