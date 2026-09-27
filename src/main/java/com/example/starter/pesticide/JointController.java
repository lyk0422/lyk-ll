package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.JointDtos.CatalogResponse;
import com.example.starter.pesticide.dto.JointDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.JointDtos.JointAssignResponse;
import com.example.starter.pesticide.dto.JointDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.JointDtos.JointCreateResponse;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineRequest;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineResponse;
import com.example.starter.pesticide.dto.JointDtos.JointOrderResponse;
import com.example.starter.pesticide.dto.JointDtos.JointResultRequest;
import com.example.starter.pesticide.dto.JointDtos.JointResultResponse;
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
 * 联检接口：疑似批次多项目联合检测的建单、分样、结果/复测、综合判定与查询。
 * 全部写操作 requestId 幂等；规则为合成规则。
 */
@RestController
@RequestMapping("/api/joint")
public class JointController {

    private final JointService jointService;

    public JointController(JointService jointService) {
        this.jointService = jointService;
    }

    /**
     * 联检建单：引用冻结快检结果与合成项目目录，冻结逐项限值与能力版本。
     */
    @PostMapping("/orders")
    public ResponseEntity<JointCreateResponse> create(@Valid @RequestBody JointCreateRequest request) {
        JointCreateResponse response = jointService.create(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /**
     * 分样分配：覆盖全部项目，任一项失败整单不分样。
     */
    @PostMapping("/assignments")
    public JointAssignResponse assign(@Valid @RequestBody JointAssignRequest request) {
        return jointService.assign(request);
    }

    /**
     * 录入结果/复测：恰等限值合格；已综合判定后只记补充信息。
     */
    @PostMapping("/results")
    public ResponseEntity<JointResultResponse> submitResult(
            @Valid @RequestBody JointResultRequest request) {
        JointResultResponse response = jointService.submitResult(request);
        HttpStatus status = (response.replayed() || response.supplementary())
                ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 综合判定：携带全部项版本冻结证据，合格放行、不合格转处置留样。
     */
    @PostMapping("/determinations")
    public JointDetermineResponse determine(@Valid @RequestBody JointDetermineRequest request) {
        return jointService.determine(request);
    }

    /**
     * 联检单详情。
     */
    @GetMapping("/orders/{orderId}")
    public JointOrderResponse detail(@PathVariable long orderId) {
        return jointService.detail(orderId);
    }

    /**
     * 合成项目目录与机构能力查询。
     */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return jointService.catalog();
    }
}
