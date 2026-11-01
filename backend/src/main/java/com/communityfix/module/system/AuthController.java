package com.communityfix.module.system;

import com.communityfix.common.*;
import com.communityfix.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    public record Login(@NotBlank @Size(max=50) String username,@NotBlank @Size(max=72) String password) {}
    public record Register(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,50}") String username,@NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=50) String nickname,
        @Pattern(regexp="RESIDENT|WORKER") String role,@Positive Long communityId) {}
    public record Wechat(@NotBlank @Size(max=200) String code) {}
    @PostMapping("/login") ApiResponse<?> login(@Valid @RequestBody Login body) { return ApiResponse.ok(service.login(body.username(),body.password())); }
    @PostMapping("/register") ApiResponse<?> register(@Valid @RequestBody Register body) { return ApiResponse.ok(service.register(body.username(),body.password(),body.nickname(),body.role(),body.communityId())); }
    @GetMapping("/registration-communities") ApiResponse<?> registrationCommunities() { return ApiResponse.ok(service.registrationCommunities()); }
    @PostMapping("/wechat-login") ApiResponse<?> wechat(@Valid @RequestBody Wechat body) { return ApiResponse.ok(service.wechat(body.code())); }
    @PostMapping("/logout") ApiResponse<?> logout() { service.logout();return ApiResponse.ok(null); }
    @GetMapping("/me") ApiResponse<?> me() { return ApiResponse.ok(CurrentUser.get()); }
}
