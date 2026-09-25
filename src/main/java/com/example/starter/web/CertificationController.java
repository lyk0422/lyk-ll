package com.example.starter.web;

import com.example.starter.service.CertificationService;
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
 * 种子批次田间检验与认证分级接口。
 */
@RestController
@RequestMapping("/api/lots")
public class CertificationController {

    private final CertificationService certificationService;

    public CertificationController(CertificationService certificationService) {
        this.certificationService = certificationService;
    }

    /** 批次申报（requestId 幂等）。 */
    @PostMapping("/declare")
    public ResponseEntity<LotView> declare(@Valid @RequestBody DeclareLotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certificationService.declare(request));
    }

    /** 田间检验（纯度）提交（requestId 幂等）。 */
    @PostMapping("/field-inspection")
    public LotView submitField(@Valid @RequestBody FieldInspectionRequest request) {
        return certificationService.submitField(request);
    }

    /** 室内检测（发芽率）提交（requestId 幂等）。 */
    @PostMapping("/lab-inspection")
    public LotView submitLab(@Valid @RequestBody LabInspectionRequest request) {
        return certificationService.submitLab(request);
    }

    /** 不合格批次复检申请（15 个自然日窗口内一次）。 */
    @PostMapping("/recheck")
    public ResponseEntity<RecheckView> applyRecheck(@Valid @RequestBody RecheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certificationService.applyRecheck(request));
    }

    /** 分级发证（按批次唯一证书、同事务核减标签）。 */
    @PostMapping("/{lotNo}/certificate")
    public LotView issueCertificate(@PathVariable String lotNo) {
        return certificationService.issueCertificate(lotNo);
    }

    /** 批次注销（终态，重复注销幂等成功）。 */
    @PostMapping("/{lotNo}/cancel")
    public LotView cancel(@PathVariable String lotNo) {
        return certificationService.cancel(new CancelRequest(lotNo));
    }

    /** 查询批次详情（含证书与检验结论）。 */
    @GetMapping("/{lotNo}")
    public LotView getLot(@PathVariable String lotNo) {
        return certificationService.getLot(lotNo);
    }
}
