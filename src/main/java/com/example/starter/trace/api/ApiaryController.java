package com.example.starter.trace.api;

import com.example.starter.trace.api.TraceDtos.AlertView;
import com.example.starter.trace.repo.TestRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 蜂场接口：预警查询。
 */
@RestController
@RequestMapping("/api/apiaries")
public class ApiaryController {

    private final TestRepository testRepository;

    public ApiaryController(TestRepository testRepository) {
        this.testRepository = testRepository;
    }

    /**
     * 查询蜂场预警列表。
     */
    @GetMapping("/{apiaryId}/alerts")
    public List<AlertView> alerts(@PathVariable Long apiaryId) {
        return testRepository.findAlertsByApiary(apiaryId);
    }
}
