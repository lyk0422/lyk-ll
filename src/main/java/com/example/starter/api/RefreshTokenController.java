package com.example.starter.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.example.starter.service.RefreshTokenService;
import jakarta.validation.Valid;

/**
 * 刷新令牌轮换与重放遏制 REST API。
 */
@RestController
@RequestMapping("/api")
public class RefreshTokenController {

    private final RefreshTokenService service;

    public RefreshTokenController(RefreshTokenService service) {
        this.service = service;
    }

    /** 创建会话：生成令牌族与首个刷新令牌。 */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse createSession(@Valid @RequestBody CreateSessionRequest request) {
        return service.createSession(request);
    }

    /** 轮换当前刷新令牌；支持安全重试幂等。 */
    @PostMapping("/tokens/rotate")
    public RotateResponse rotate(@Valid @RequestBody RotateRequest request) {
        return service.rotate(request);
    }

    /** 登出：撤销整个令牌族；重复登出幂等无副作用。 */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        service.logout(request);
    }

    /** 查询令牌族状态。 */
    @GetMapping("/families/{familyId}")
    public FamilyStatusResponse familyStatus(@PathVariable String familyId) {
        return service.familyStatus(familyId);
    }

    /** 查询族下安全事件（REPLAY_DETECTED）。 */
    @GetMapping("/families/{familyId}/events")
    public List<SecurityEventResponse> events(@PathVariable String familyId) {
        return service.events(familyId);
    }
}
