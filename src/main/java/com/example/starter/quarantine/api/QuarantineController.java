package com.example.starter.quarantine.api;

import com.example.starter.quarantine.api.ApiDtos.AlertHandleCmd;
import com.example.starter.quarantine.api.ApiDtos.AlertScanView;
import com.example.starter.quarantine.api.ApiDtos.AlertView;
import com.example.starter.quarantine.api.ApiDtos.ArrivalCmd;
import com.example.starter.quarantine.api.ApiDtos.ArrivalView;
import com.example.starter.quarantine.api.ApiDtos.CertificateView;
import com.example.starter.quarantine.api.ApiDtos.DeclarationCmd;
import com.example.starter.quarantine.api.ApiDtos.DeclarationView;
import com.example.starter.quarantine.api.ApiDtos.DepartCmd;
import com.example.starter.quarantine.api.ApiDtos.IssueCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownCmd;
import com.example.starter.quarantine.api.ApiDtos.LockdownView;
import com.example.starter.quarantine.api.ApiDtos.StockCmd;
import com.example.starter.quarantine.api.ApiDtos.StockView;
import com.example.starter.quarantine.api.ApiDtos.TrajectoryView;
import com.example.starter.quarantine.service.IssueExecutor;
import com.example.starter.quarantine.service.QuarantineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动物调运检疫出证与疫点封锁联动服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class QuarantineController {

    private final QuarantineService service;
    private final IssueExecutor issueExecutor;

    public QuarantineController(QuarantineService service, IssueExecutor issueExecutor) {
        this.service = service;
        this.issueExecutor = issueExecutor;
    }

    /** 检疫申报（幂等）。 */
    @PostMapping("/quarantine/declarations")
    public DeclarationView declare(@Valid @RequestBody DeclarationCmd cmd) {
        return service.declare(cmd);
    }

    /** 查询申报单。 */
    @GetMapping("/quarantine/declarations/{requestId}")
    public DeclarationView getDeclaration(@PathVariable String requestId) {
        return service.getDeclaration(requestId);
    }

    /** 出证（幂等；核减存栏与生成证号同事务）。 */
    @PostMapping("/quarantine/certificates")
    public CertificateView issue(@Valid @RequestBody IssueCmd cmd) {
        return issueExecutor.execute(cmd);
    }

    /** 查询合格证。 */
    @GetMapping("/quarantine/certificates/{certNo}")
    public CertificateView getCertificate(@PathVariable String certNo) {
        return service.getCertificate(certNo);
    }

    /** 启运：已出证未启运 → 调运在途。 */
    @PostMapping("/quarantine/departures")
    public CertificateView depart(@Valid @RequestBody DepartCmd cmd) {
        return service.depart(cmd);
    }

    /** 发布疫点封锁（即刻生效，三级联动）。 */
    @PostMapping("/quarantine/lockdowns")
    public LockdownView lockdown(@Valid @RequestBody LockdownCmd cmd) {
        return service.lockdown(cmd);
    }

    /** 落地报告（幂等；结案为终态）。 */
    @PostMapping("/quarantine/arrivals")
    public ArrivalView arrive(@Valid @RequestBody ArrivalCmd cmd) {
        return service.arrive(cmd);
    }

    /** 登记/覆盖可售存栏（合成数据入口）。 */
    @PostMapping("/stocks")
    public StockView upsertStock(@Valid @RequestBody StockCmd cmd) {
        return service.upsertStock(cmd);
    }

    /** 查询可售存栏。 */
    @GetMapping("/stocks")
    public StockView getStock(@RequestParam String farmId,
                              @RequestParam String species,
                              @RequestParam String earTagRange) {
        return service.getStock(farmId, species, earTagRange);
    }

    /** 扫描超时未核销的过境在途证并生成预警（幂等）。 */
    @PostMapping("/quarantine/transit-alerts/scan")
    public AlertScanView scanTransitOverdue() {
        return service.scanTransitOverdue();
    }

    /** 查询预警事件。 */
    @GetMapping("/quarantine/transit-alerts/{id}")
    public AlertView getAlert(@PathVariable long id) {
        return service.getAlert(id);
    }

    /** 预警事件处理闭环（幂等）。 */
    @PostMapping("/quarantine/transit-alerts/handle")
    public AlertView handleAlert(@Valid @RequestBody AlertHandleCmd cmd) {
        return service.handleAlert(cmd);
    }

    /** 证书轨迹查询：证书状态 + 落地报告 + 预警事件（审计口径）。 */
    @GetMapping("/quarantine/certificates/{certNo}/trajectory")
    public TrajectoryView getTrajectory(@PathVariable String certNo) {
        return service.getTrajectory(certNo);
    }
}
