package com.lrj.recon.batch.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 将统一 Casdoor 的 permissions/roles/groups/scope claim 映射为本地 API authority (对齐 risk-platform
 * {@code CasdoorAuthorityMapper})。
 *
 * <ul>
 *   <li><b>permissions</b>:能力字符串 (如 {@code recon.launch}) 或对象 {@code {"name":"recon.launch"}} → 直接作 authority;</li>
 *   <li><b>roles/groups</b>:取最后一段 (全路径 {@code org/recon-admin} → {@code recon-admin}) → {@code ROLE_RECON_ADMIN};</li>
 *   <li><b>scope/scp</b>:空格分隔 → {@code SCOPE_<x>}。</li>
 * </ul>
 * principal name = {@code sub} (稳定身份主键)。recon 授权矩阵按 <b>permissions</b> 判 (Casdoor 是角色→权限的唯一真相源)。
 */
public final class CasdoorAuthorityMapper {

    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::fromJwt);
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    private Collection<GrantedAuthority> fromJwt(Jwt jwt) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>();
        Map<String, Object> claims = jwt.getClaims();
        addPermissions(mapped, claims.get("permissions"));
        addRoleLike(mapped, claims.get("roles"));
        addRoleLike(mapped, claims.get("groups"));
        addScope(mapped, claims.get("scope"));
        addScope(mapped, claims.get("scp"));
        return mapped;
    }

    private void addPermissions(Set<GrantedAuthority> mapped, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> addPermission(mapped, value instanceof Map<?, ?> p ? p.get("name") : value));
        } else {
            addPermission(mapped, claim);
        }
    }

    private void addPermission(Set<GrantedAuthority> mapped, Object value) {
        if (value == null) return;
        String permission = String.valueOf(value).trim();
        if (!permission.isEmpty()) mapped.add(new SimpleGrantedAuthority(permission));
    }

    private void addRoleLike(Set<GrantedAuthority> mapped, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> {
                Object name = value instanceof Map<?, ?> item ? item.get("name") : value;
                if (name != null) addRole(mapped, String.valueOf(name));
            });
        } else if (claim instanceof Map<?, ?> item) {
            Object name = item.get("name");
            if (name != null) addRole(mapped, String.valueOf(name));
        } else if (claim instanceof String value) {
            for (String item : value.split("[ ,]+")) addRole(mapped, item);
        }
    }

    private void addRole(Set<GrantedAuthority> mapped, String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) return;
        int slash = normalized.lastIndexOf('/');
        String shortName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        mapped.add(new SimpleGrantedAuthority("ROLE_" + shortName.toUpperCase().replace('-', '_')));
    }

    private void addScope(Set<GrantedAuthority> mapped, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> mapped.add(new SimpleGrantedAuthority("SCOPE_" + value)));
        } else if (claim instanceof String value) {
            for (String scope : value.split(" ")) {
                if (!scope.isBlank()) mapped.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        }
    }
}
