package com.example.starter.web;

import com.example.starter.domain.BreachEvent;
import com.example.starter.domain.BrakeCommand;
import com.example.starter.repo.BrakeRepository;
import com.example.starter.repo.BreachRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 越限事件与制动指令查询接口。预警与制动记录只读，不提供删除/修改入口（修正须以新记录留痕）。
 */
@RestController
@RequestMapping("/api/cranes")
public class QueryController {

    private final BreachRepository breachRepo;
    private final BrakeRepository brakeRepo;

    public QueryController(BreachRepository breachRepo, BrakeRepository brakeRepo) {
        this.breachRepo = breachRepo;
        this.brakeRepo = brakeRepo;
    }

    @GetMapping("/{craneId}/breach-events")
    public List<BreachEvent> breachEvents(@PathVariable String craneId) {
        return breachRepo.findByCrane(craneId);
    }

    @GetMapping("/{craneId}/brake-commands")
    public List<BrakeCommand> brakeCommands(@PathVariable String craneId) {
        return brakeRepo.findByCrane(craneId);
    }
}
