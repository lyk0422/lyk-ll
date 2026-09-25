package com.example.starter.web;

import java.util.List;

import com.example.starter.service.CurtailmentService;
import com.example.starter.service.ReportService;
import com.example.starter.web.dto.AlarmView;
import com.example.starter.web.dto.CommandAccepted;
import com.example.starter.web.dto.CommandDetail;
import com.example.starter.web.dto.CommandRequest;
import com.example.starter.web.dto.CurvePoint;
import com.example.starter.web.dto.ReportAccepted;
import com.example.starter.web.dto.ReportRequest;
import com.example.starter.web.dto.SealAccepted;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风电场限电出力分配与执行回报 API。
 */
@RestController
@RequestMapping("/api/curtailment")
public class CurtailmentController {

    private final CurtailmentService curtailmentService;
    private final ReportService reportService;

    public CurtailmentController(CurtailmentService curtailmentService,
                                 ReportService reportService) {
        this.curtailmentService = curtailmentService;
        this.reportService = reportService;
    }

    /**
     * 接收限电指令：分摊并下发，requestId 幂等。
     */
    @PostMapping("/commands")
    public CommandAccepted receive(@Valid @RequestBody CommandRequest request) {
        return curtailmentService.receive(request);
    }

    /**
     * 指令详情：含全部分摊轮次，可复算。
     */
    @GetMapping("/commands/{id}")
    public CommandDetail detail(@PathVariable long id) {
        return curtailmentService.detail(id);
    }

    /**
     * 日结封存（终态），重复封存幂等成功。
     */
    @PostMapping("/commands/{id}/seal")
    public SealAccepted seal(@PathVariable long id) {
        return curtailmentService.seal(id);
    }

    /**
     * 接收执行回报：机组＋时刻去重，超时告警并重分摊。
     */
    @PostMapping("/reports")
    public ReportAccepted report(@Valid @RequestBody ReportRequest request) {
        return reportService.submit(request);
    }

    /**
     * 执行曲线：乱序回报按采集时刻归位。
     */
    @GetMapping("/commands/{id}/curve")
    public List<CurvePoint> curve(@PathVariable long id) {
        return reportService.curve(id);
    }

    /**
     * 告警链。
     */
    @GetMapping("/commands/{id}/alarms")
    public List<AlarmView> alarms(@PathVariable long id) {
        return reportService.alarms(id);
    }
}
