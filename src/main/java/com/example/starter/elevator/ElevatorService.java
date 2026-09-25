package com.example.starter.elevator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiException;

/**
 * 电梯台账服务：登记合成电梯、查询状态与停复梯链。
 */
@Service
public class ElevatorService {

    private final ElevatorRepository elevatorRepository;
    private final Clock clock;

    public ElevatorService(ElevatorRepository elevatorRepository, Clock clock) {
        this.elevatorRepository = elevatorRepository;
        this.clock = clock;
    }

    /**
     * 登记电梯：初始运行中，最近维保日期取当日（UTC 日界）；编号重复返回 409。
     */
    @Transactional
    public Elevator create(String code) {
        if (elevatorRepository.findByCode(code).isPresent()) {
            throw ApiException.conflict("电梯编号已存在: " + code);
        }
        Instant now = Instant.now(clock);
        long id = elevatorRepository.insert(code, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        return elevatorRepository.findById(id).orElseThrow();
    }

    /**
     * 查询电梯。
     */
    @Transactional(readOnly = true)
    public Elevator get(String code) {
        return elevatorRepository.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("电梯不存在: " + code));
    }

    /**
     * 查询停复梯链（可复算）。
     */
    @Transactional(readOnly = true)
    public List<ElevatorRepository.StopLog> stopLog(String code) {
        Elevator elevator = get(code);
        return elevatorRepository.listStopLog(elevator.id());
    }
}
