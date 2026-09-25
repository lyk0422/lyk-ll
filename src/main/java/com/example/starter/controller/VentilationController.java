package com.example.starter.controller;

import java.util.List;

import com.example.starter.dto.GrainDtos.CreateJobRequest;
import com.example.starter.dto.GrainDtos.JobView;
import com.example.starter.dto.GrainDtos.RecheckRequest;
import com.example.starter.dto.GrainDtos.RecheckView;
import com.example.starter.service.VentilationService;

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
 * 通风作业接口：作业下达、完成、复测、归档及作业窗/复测对比查询。
 */
@RestController
@RequestMapping("/api")
public class VentilationController {

    private final VentilationService ventilationService;

    public VentilationController(VentilationService ventilationService) {
        this.ventilationService = ventilationService;
    }

    /**
     * 下达通风作业：半开作业窗占用、湿度边界校验、风机原子锁定、requestId 幂等。
     */
    @PostMapping("/silos/{siloId}/ventilation-jobs")
    public ResponseEntity<JobView> createJob(@PathVariable long siloId,
                                             @Valid @RequestBody CreateJobRequest request) {
        JobView view = ventilationService.createJob(siloId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 查询通风作业。 */
    @GetMapping("/ventilation-jobs/{jobId}")
    public JobView getJob(@PathVariable long jobId) {
        return ventilationService.getJob(jobId);
    }

    /** 完成通风作业：释放风机，粮仓转入效果复测。 */
    @PostMapping("/ventilation-jobs/{jobId}/complete")
    public JobView completeJob(@PathVariable long jobId) {
        return ventilationService.completeJob(jobId);
    }

    /** 效果复测：梯度超标判定与两轮上限控制，requestId 幂等。 */
    @PostMapping("/ventilation-jobs/{jobId}/rechecks")
    public ResponseEntity<RecheckView> recheck(@PathVariable long jobId,
                                               @Valid @RequestBody RecheckRequest request) {
        RecheckView view = ventilationService.recheck(jobId, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /** 归档作业（终态）：重复归档返回幂等成功。 */
    @PostMapping("/ventilation-jobs/{jobId}/archive")
    public JobView archive(@PathVariable long jobId) {
        return ventilationService.archive(jobId);
    }

    /** 粮仓作业窗列表（半开区间 [start, end)，可复算占用）。 */
    @GetMapping("/silos/{siloId}/ventilation-windows")
    public List<JobView> windows(@PathVariable long siloId) {
        return ventilationService.windows(siloId);
    }

    /** 粮仓全部复测记录（含轮次），两轮复测对比可复算。 */
    @GetMapping("/silos/{siloId}/rechecks")
    public List<RecheckView> rechecksOfSilo(@PathVariable long siloId) {
        return ventilationService.rechecksOfSilo(siloId);
    }
}
