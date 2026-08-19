package com.lrj.recon.batch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A1 认证鉴权:统一身份平台 (auth-platform / Casdoor) 签发 token 的强校验契约。
 *
 * <p>对齐 risk-platform {@code RiskIdentityProperties} 范式:JWKS 验签 + issuer + audience allowlist + owner(组织)
 * + 非空 sub。仅在 {@code secure} profile 生效 ({@link CasdoorSecurityConfig});本地/测试 {@code dev} profile
 * 走 {@link DevSecurityConfig} permitAll,无需这些配置。
 */
@ConfigurationProperties(prefix = "recon.auth")
public class ReconAuthProperties {

    /** OIDC issuer (精确匹配 token iss)。 */
    private String issuer = "http://localhost:8000";

    /** JWKS 端点 (验签公钥;NimbusJwtDecoder 懒加载,构造不联网)。 */
    private String jwkSetUri = "http://localhost:8000/.well-known/jwks";

    /** 组织 (租户):token owner 必须等于它。 */
    private String organization = "recon-platform";

    /** 允许的 audience (client_id) allowlist:token aud 至少命中其一。 */
    private List<String> audiences = new ArrayList<>();

    /** 人工核销落库的操作者取自该 claim (缺失回退 sub)。 */
    private String operatorClaim = "preferred_username";

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public List<String> getAudiences() { return audiences; }
    public void setAudiences(List<String> audiences) { this.audiences = audiences; }
    public String getOperatorClaim() { return operatorClaim; }
    public void setOperatorClaim(String operatorClaim) { this.operatorClaim = operatorClaim; }
}
