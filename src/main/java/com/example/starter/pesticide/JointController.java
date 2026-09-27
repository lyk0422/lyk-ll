package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAllocateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointDetailResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResponse;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultResponse;
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
 * 定量联检接口：联检创建、分样、逐项结果与综合判定（均 requestId 幂等，修改携带 expectedVersion）。
 */
@RestController
@RequestMapping("/api/joint-inspections")
public class JointController {

    private final JointService jointService;

    public JointController(JointService jointService) {
        this.jointService = jointService;
    }

    /**
     * 创建联检单：疑似冻结批次引用冻结快检结果与合成项目目录，冻结逐项限值与机构能力版本。
     */
    @PostMapping
    public ResponseEntity<JointResponse> create(@Valid @RequestBody JointCreateRequest request) {
        JointResponse response = jointService.create(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 分样分配：覆盖全部项目、样本号全局唯一，任一项失败整单不分样。
     */
    @PostMapping("/{jointId}/allocations")
    public ResponseEntity<JointAllocateResponse> allocate(@PathVariable long jointId,
                                                          @Valid @RequestBody JointAllocateRequest request) {
        JointAllocateResponse response = jointService.allocate(jointId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 录入/复测联检结果：综合判定前替换当前结果并保留旧版本；判定后只记补充信息。
     */
    @PostMapping("/{jointId}/results")
    public ResponseEntity<JointResultResponse> addResult(@PathVariable long jointId,
                                                         @Valid @RequestBody JointResultRequest request) {
        JointResultResponse response = jointService.addResult(jointId, request);
        HttpStatus status = (response.replayed() || response.supplemental())
                ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 综合判定：携带全部项版本冻结证据；合格放行、不合格转处置留样（同一事务）。
     */
    @PostMapping("/{jointId}/decision")
    public ResponseEntity<JointDecisionResponse> decide(@PathVariable long jointId,
                                                        @Valid @RequestBody JointDecisionRequest request) {
        JointDecisionResponse response = jointService.decide(jointId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 联检单详情。
     */
    @GetMapping("/{jointId}")
    public JointDetailResponse detail(@PathVariable long jointId) {
        return jointService.detail(jointId);
    }
}
