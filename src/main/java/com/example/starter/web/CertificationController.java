package com.example.starter.web;

import com.example.starter.service.CertificationService;
import com.example.starter.service.CertificationService.ReplayedRequest;
import com.example.starter.web.dto.CertificateIssueResponse;
import com.example.starter.web.dto.DeclareLotRequest;
import com.example.starter.web.dto.FieldInspectionRequest;
import com.example.starter.web.dto.InspectionView;
import com.example.starter.web.dto.LabInspectionRequest;
import com.example.starter.web.dto.LotDetailView;
import com.example.starter.web.dto.LotRevokeResponse;
import com.example.starter.web.dto.LotView;
import com.example.starter.web.dto.RevokeLotRequest;
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
 * 种子批次认证 API：
 * <ul>
 *     <li>POST /api/lots/declarations           批次申报（requestId 幂等）</li>
 *     <li>POST /api/lots/{lotNo}/field-inspections  田间检验提交（首检/复检，requestId 幂等）</li>
 *     <li>POST /api/lots/{lotNo}/lab-inspections    室内检测提交（首检/复检，requestId 幂等）</li>
 *     <li>POST /api/lots/{lotNo}/certifications 分级发证</li>
 *     <li>POST /api/lots/{lotNo}/revocation     批次注销（终态，幂等重入）</li>
 *     <li>GET  /api/lots/{lotNo}                批次详情（含全部检验记录与留痕）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/lots")
public class CertificationController {

    private final CertificationService service;

    public CertificationController(CertificationService service) {
        this.service = service;
    }

    /** 批次申报。 */
    @PostMapping("/declarations")
    public ResponseEntity<?> declare(@Valid @RequestBody DeclareLotRequest request) {
        return replayOrOk(service.declare(request));
    }

    /** 田间检验提交（round=FIRST 首检，RETEST 不合格后 15 个自然日内复检一次）。 */
    @PostMapping("/{lotNo}/field-inspections")
    public ResponseEntity<?> submitField(@PathVariable String lotNo,
                                         @Valid @RequestBody FieldInspectionRequest request) {
        return replayOrOk(service.submitField(request));
    }

    /** 室内检测提交（round=FIRST 首检，RETEST 复检）。 */
    @PostMapping("/{lotNo}/lab-inspections")
    public ResponseEntity<?> submitLab(@PathVariable String lotNo,
                                       @Valid @RequestBody LabInspectionRequest request) {
        return replayOrOk(service.submitLab(request));
    }

    /** 分级发证：两项有效结论齐备且达标；同批次并发发证仅一次成功。 */
    @PostMapping("/{lotNo}/certifications")
    public CertificateIssueResponse certify(@PathVariable String lotNo) {
        return service.certify(lotNo);
    }

    /** 批次注销（终态，重复注销幂等成功，已发证书同步作废并留痕）。 */
    @PostMapping("/{lotNo}/revocation")
    public LotRevokeResponse revoke(@PathVariable String lotNo,
                                    @Valid @RequestBody RevokeLotRequest request) {
        return service.revoke(lotNo, request);
    }

    /** 批次详情：双检记录（含被复检覆盖的首检）、证书、留痕。 */
    @GetMapping("/{lotNo}")
    public LotDetailView detail(@PathVariable String lotNo) {
        return service.getDetail(lotNo);
    }

    /**
     * 服务层返回同键重放结果时按首次状态码/正文响应，否则按 200 返回新结果。
     */
    private static ResponseEntity<?> replayOrOk(Object result) {
        if (result instanceof ReplayedRequest replayed) {
            return ResponseEntity.status(replayed.httpStatus()).body(replayed.body());
        }
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}
