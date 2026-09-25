package com.example.starter.quarantine.api;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.BlockadeView;
import com.example.starter.quarantine.api.dto.CaseView;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.DepartRequest;
import com.example.starter.quarantine.api.dto.IssueCertificateRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.api.dto.LandingResultView;
import com.example.starter.quarantine.service.QuarantineService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动物检疫服务 HTTP 入口。所有写操作以业务键幂等。
 */
@RestController
@RequestMapping("/api/quarantine")
public class QuarantineController {

    private final QuarantineService service;

    public QuarantineController(QuarantineService service) {
        this.service = service;
    }

    /** 检疫申报。同键同参返回首次结果（重放标记=true）；同键异参 409。封锁即刻生效时直接驳回。 */
    @PostMapping("/declarations")
    public ResponseEntity<CaseView> declare(@Valid @RequestBody DeclareRequest request) {
        return ResponseEntity.ok(service.declare(request));
    }

    /** 产地检疫合格后出证：核减存栏、生成证号，同事务；同批动物有效证唯一。 */
    @PostMapping("/certificates")
    public CaseView issue(@Valid @RequestBody IssueCertificateRequest request) {
        return service.issue(request.requestId());
    }

    /** 启运报告：单据进入调运在途。 */
    @PostMapping("/departures")
    public CaseView depart(@Valid @RequestBody DepartRequest request) {
        return service.depart(request.requestId());
    }

    /** 落地报告：支持分批，累计超量整批拒绝，恰满结案，结案后迟到事件只记录不改状态。 */
    @PostMapping("/landings")
    public LandingResultView landing(@Valid @RequestBody LandingReportRequest request) {
        return service.landing(request);
    }

    /** 疫点封锁发布，即刻生效并三级联动。 */
    @PostMapping("/blockades")
    public BlockadeView blockade(@Valid @RequestBody BlockadeRequest request) {
        return service.publishBlockade(request);
    }
}
