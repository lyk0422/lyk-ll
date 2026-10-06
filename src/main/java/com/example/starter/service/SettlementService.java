package com.example.starter.service;

import java.time.LocalDate;
import java.util.List;

import com.example.starter.repo.NotificationRepository;
import com.example.starter.repo.StudentRepository;
import com.example.starter.web.dto.QualificationDtos.ReassignmentItem;
import com.example.starter.web.dto.QualificationDtos.SettlementResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日结服务：按 UTC 日界复算打卡、漏乘通知与改派记录。
 */
@Service
public class SettlementService {

    private final StudentRepository studentRepository;
    private final NotificationRepository notificationRepository;

    public SettlementService(StudentRepository studentRepository,
                             NotificationRepository notificationRepository) {
        this.studentRepository = studentRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * 复算指定日期的日结数据；改派记录来自 reassignment 表，可重复计算结果一致。
     */
    @Transactional(readOnly = true)
    public SettlementResponse settle(LocalDate date) {
        long checkInCount = studentRepository.countCheckInsByDate(date);
        long missedCount = notificationRepository.countNotifications("MISSED_BOARDING", date);
        List<NotificationRepository.ReassignmentRow> reassignments =
                notificationRepository.findReassignmentsBySettleDate(date);
        List<ReassignmentItem> items = reassignments.stream()
                .map(r -> new ReassignmentItem(r.studentId(), r.fromRouteId(), r.toRouteId(),
                        r.reason()))
                .toList();
        return new SettlementResponse(date, checkInCount, missedCount, items.size(), items);
    }
}
