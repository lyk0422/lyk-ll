package com.example.starter.web;

import com.example.starter.dto.ColdChainCorrectRequest;
import com.example.starter.dto.ColdChainReadingsSubmitRequest;
import com.example.starter.dto.ColdChainSegmentRegisterRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.service.ColdChainService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控包装子批冷链片段核验 REST 入口：运输片段登记、温度读数提交（重核）、
 * 补录/更正与台账查询。签发门禁仍在批签发原签发入口同事务检查当前温证。
 */
@RestController
@RequestMapping("/api/coldchain/subbatches")
public class ColdChainController {

    private final ColdChainService coldChainService;

    public ColdChainController(ColdChainService coldChainService) {
        this.coldChainService = coldChainService;
    }

    /** 登记运输片段（requestId 幂等）：创建台账版本 1 并冻结完整运输起止。 */
    @PostMapping("/{batchNo}/segments")
    public ResponseEntity<Object> registerSegments(@PathVariable String batchNo,
                                                   @Valid @RequestBody ColdChainSegmentRegisterRequest request) {
        // 路径批号优先，避免路径与请求体不一致造成歧义。
        ColdChainSegmentRegisterRequest resolved = new ColdChainSegmentRegisterRequest(
                request.requestId(), batchNo, request.segments(), request.at());
        return toEntity(coldChainService.registerSegments(resolved));
    }

    /** 提交温度读数并重核（requestId 幂等，失败不占键）：完整证据合格即冻结读数版本。 */
    @PostMapping("/{batchNo}/readings")
    public ResponseEntity<Object> submitReadings(@PathVariable String batchNo,
                                                 @Valid @RequestBody ColdChainReadingsSubmitRequest request) {
        ColdChainReadingsSubmitRequest resolved = new ColdChainReadingsSubmitRequest(
                request.requestId(), batchNo, request.expectedVersion(),
                request.segments(), request.at());
        return toEntity(coldChainService.submitReadings(resolved));
    }

    /** 补录/更正运输片段（requestId 幂等，失败不占键）：版本加 1，旧温证资格失效须重核。 */
    @PostMapping("/{batchNo}/corrections")
    public ResponseEntity<Object> correct(@PathVariable String batchNo,
                                          @Valid @RequestBody ColdChainCorrectRequest request) {
        ColdChainCorrectRequest resolved = new ColdChainCorrectRequest(
                request.requestId(), batchNo, request.expectedVersion(),
                request.segments(), request.at());
        return toEntity(coldChainService.correct(resolved));
    }

    /** 查询冷链台账视图：当前版本证据、缺口、越界点及温证冻结版本。 */
    @GetMapping("/{batchNo}")
    public ResponseEntity<Object> view(@PathVariable String batchNo) {
        return ResponseEntity.ok(coldChainService.getView(batchNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
