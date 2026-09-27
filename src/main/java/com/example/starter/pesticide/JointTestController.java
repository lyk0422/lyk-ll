package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitResponse;
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
 * 定量联检接口：疑似批次联检创建、分样、结果/复测与综合判定（均 requestId 幂等、版本门控）。
 */
@RestController
@RequestMapping("/api/joint-tests")
public class JointTestController {

    private final JointTestService jointTestService;

    public JointTestController(JointTestService jointTestService) {
        this.jointTestService = jointTestService;
    }

    /**
     * 联检创建：疑似冻结批次建立联检单，逐项限值与机构能力版本一并冻结。
     */
    @PostMapping
    public ResponseEntity<JointTestCreateResponse> create(
            @Valid @RequestBody JointTestCreateRequest request) {
        JointTestCreateResponse response = jointTestService.create(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 分样分配：覆盖全部项目、样本号全局唯一，任一项失败整单不分样。
     */
    @PostMapping("/{orderId}/splits")
    public ResponseEntity<JointTestSplitResponse> split(
            @PathVariable long orderId, @Valid @RequestBody JointTestSplitRequest request) {
        JointTestSplitResponse response = jointTestService.split(orderId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 联检结果/复测：恰等限值合格；判定前复测替换当前结果，判定后只记补充信息。
     */
    @PostMapping("/{orderId}/results")
    public ResponseEntity<JointTestResultResponse> submitResult(
            @PathVariable long orderId, @Valid @RequestBody JointTestResultRequest request) {
        JointTestResultResponse response = jointTestService.submitResult(orderId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 综合判定：携带全部项目结果版本；合格同事务放行，不合格转处置并留样。
     */
    @PostMapping("/{orderId}/judgment")
    public JointTestJudgeResponse judge(@PathVariable long orderId,
                                        @Valid @RequestBody JointTestJudgeRequest request) {
        return jointTestService.judge(orderId, request);
    }

    /**
     * 联检单详情。
     */
    @GetMapping("/{orderId}")
    public JointTestDetailResponse detail(@PathVariable long orderId) {
        return jointTestService.detail(orderId);
    }
}
