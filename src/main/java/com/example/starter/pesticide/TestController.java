package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.RecheckRequest;
import com.example.starter.pesticide.dto.ApiDtos.RecheckResponse;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检测接口：农残快检与定量复检（均 requestId 幂等）。
 */
@RestController
@RequestMapping("/api")
public class TestController {

    private final TestService testService;

    public TestController(TestService testService) {
        this.testService = testService;
    }

    /**
     * 快检：抑制率达到 50 判疑似并冻结准入。
     */
    @PostMapping("/tests")
    public ResponseEntity<TestResponse> rapidTest(@Valid @RequestBody TestRequest request) {
        TestResponse response = testService.rapidTest(request);
        HttpStatus status = (response.replayed() || response.late()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 定量复检：合格放行、不合格转监督处置并留样 48 小时。
     */
    @PostMapping("/rechecks")
    public ResponseEntity<RecheckResponse> recheck(@Valid @RequestBody RecheckRequest request) {
        RecheckResponse response = testService.recheck(request);
        HttpStatus status = (response.replayed() || response.late()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
