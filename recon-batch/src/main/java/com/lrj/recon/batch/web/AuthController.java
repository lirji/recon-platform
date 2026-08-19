package com.lrj.recon.batch.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A1 当前会话身份 (前端权限门控的权威来源,对齐 risk-platform {@code /api/v1/auth/me})。
 *
 * <ul>
 *   <li><b>secure</b> profile:从可信 JWT 取 sub / 展示名 ({@code recon.auth.operator-claim}) + permissions
 *       (Authentication authorities 去除 ROLE_/SCOPE_ 前缀者);仅需已认证 (授权 matrix 放行 {@code /recon/auth/**})。</li>
 *   <li><b>dev</b> profile (permitAll, 无 JWT):返回具全权限的本地开发身份,使本地 UI 展示全部控件。</li>
 * </ul>
 * 前端 {@code useAuth().can(permission)} 据此显隐/放行写操作 (前端仅体验层,后端授权仍是安全边界)。
 */
@RestController
@RequestMapping("/recon/auth")
public class AuthController {

    private final String operatorClaim;

    public AuthController(@Value("${recon.auth.operator-claim:preferred_username}") String operatorClaim) {
        this.operatorClaim = operatorClaim;
    }

    @GetMapping("/me")
    public UserSession me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        if (jwt == null) {
            // dev / 本地:无 JWT → 全权限开发身份 (与 DevSecurityConfig permitAll 一致)。
            return new UserSession(true, "dev", "dev-operator",
                    List.of("recon.read", "recon.dispose", "recon.launch"));
        }
        String name = displayName(jwt, operatorClaim);
        List<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith("ROLE_") && !a.startsWith("SCOPE_"))
                .toList();
        return new UserSession(true, jwt.getSubject(), name, permissions);
    }

    /** 展示/审计用可读身份名:{@code operator-claim}(默认 preferred_username)→ Casdoor {@code name}(用户名)→ {@code sub}。 */
    static String displayName(Jwt jwt, String operatorClaim) {
        String primary = jwt.getClaimAsString(operatorClaim);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        return jwt.getSubject();
    }

    /** 会话身份投影 (前端 {@code UserSession})。 */
    public record UserSession(boolean authenticated, String sub, String name, List<String> permissions) {
    }
}
