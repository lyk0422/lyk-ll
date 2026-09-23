package com.example.starter.token.api;

import com.example.starter.token.TokenService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地刷新令牌 API 骨架。
 */
@RestController
@RequestMapping("/api/sessions")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public TokenIssuedResponse create(@Valid @RequestBody CreateSessionRequest request) {
        return tokenService.createSession(request);
    }

    @PostMapping("/rotate")
    public TokenIssuedResponse rotate(@Valid @RequestBody RotateRequest request) {
        return tokenService.rotate(request);
    }

    @PostMapping("/logout")
    public void logout(@Valid @RequestBody LogoutRequest request) {
        tokenService.logout(request);
    }

    @GetMapping("/{familyId}")
    public FamilyStatusResponse status(@PathVariable String familyId) {
        return tokenService.familyStatus(familyId);
    }

    @GetMapping("/{familyId}/events")
    public List<SecurityEventResponse> events(@PathVariable String familyId) {
        return tokenService.events(familyId);
    }
}
