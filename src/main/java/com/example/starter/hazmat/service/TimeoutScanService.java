package com.example.starter.hazmat.service;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.model.Waybill;
import com.example.starter.hazmat.repo.WaybillRepository;
import com.example.starter.hazmat.web.ResultView;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时复算服务：对在途运单按当前时刻复算途经点超时（计划通过时刻 + 60 分钟，恰满判定），
 * 触发偏离预警与连续 2 点失联升级。供定时任务与手动复算入口调用。
 */
@Service
public class TimeoutScanService {

    private final WaybillRepository waybills;
    private final CheckinService checkinService;
    private final Clock clock;

    public TimeoutScanService(WaybillRepository waybills, CheckinService checkinService,
                              Clock clock) {
        this.waybills = waybills;
        this.checkinService = checkinService;
        this.clock = clock;
    }

    /**
     * 对全部在途运单执行一次超时复算，返回扫描统计。
     */
    @Transactional
    public ResultView scanOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Waybill> inTransit = waybills.findByStatus(WaybillStatus.IN_TRANSIT);
        int escalated = 0;
        for (Waybill waybill : inTransit) {
            String escalation = checkinService.evaluateTimeouts(
                    waybill, waybill.routeGeneration(), now);
            if (escalation != null) {
                escalated++;
            }
        }
        return ResultView.ok(String.format("复算完成：在途运单 %d 单，升级失联 %d 单",
                inTransit.size(), escalated));
    }
}
