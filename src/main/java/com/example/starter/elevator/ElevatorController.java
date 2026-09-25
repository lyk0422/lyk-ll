package com.example.starter.elevator;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 电梯台账接口：登记合成电梯、查询状态与停复梯链。
 */
@RestController
@RequestMapping("/api/elevators")
public class ElevatorController {

    private final ElevatorService elevatorService;

    public ElevatorController(ElevatorService elevatorService) {
        this.elevatorService = elevatorService;
    }

    /**
     * 登记电梯请求。
     */
    public record CreateElevatorRequest(@NotBlank String code) {
    }

    @PostMapping
    public Elevator create(@RequestBody @Valid CreateElevatorRequest req) {
        return elevatorService.create(req.code());
    }

    @GetMapping("/{code}")
    public Elevator get(@PathVariable String code) {
        return elevatorService.get(code);
    }

    @GetMapping("/{code}/stop-log")
    public List<ElevatorRepository.StopLog> stopLog(@PathVariable String code) {
        return elevatorService.stopLog(code);
    }
}
