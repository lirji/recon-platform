import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  ApartmentOutlined,
  ArrowRightOutlined,
  AuditOutlined,
  CheckCircleFilled,
  LoginOutlined,
  NodeIndexOutlined,
  SafetyCertificateOutlined,
  SolutionOutlined,
} from '@ant-design/icons'
import { Alert, Button, Form, Input } from 'antd'
import { AUTH_CONFIG, AUTH_MODE } from '../auth/config'
import { useAuth } from '../auth/AuthContext'
import { validateTenantSelection } from '../auth/tenantSelection'
import '../styles/login.css'

/**
 * 登录页(AppLayout 之外的独立顶层路由)。对齐 risk/activity 系登录形态:统一浮层卡(左深色品牌 hero +
 * 右白色表单),环境光背景。oidc 模式确认组织后授权码 + PKCE 跳 Casdoor;dev 模式一键进入本地开发身份。
 */
const FEATURES = [
  { icon: <NodeIndexOutlined />, title: '三方桥接对账', desc: '营销 · 账务 · 渠道两段桥接,自动匹配到笔' },
  { icon: <SolutionOutlined />, title: '差异闭环处置', desc: '判差 · 冲正 · 核销一站式,保护人工处置' },
  { icon: <SafetyCertificateOutlined />, title: '多租户安全隔离', desc: 'Casdoor 统一身份与组织边界校验' },
]

export function LoginPage() {
  const auth = useAuth()
  const [params] = useSearchParams()
  const [tenant, setTenant] = useState(AUTH_CONFIG.organization)
  const [tenantError, setTenantError] = useState('')
  const isOidc = AUTH_MODE === 'oidc'

  async function submit(event: FormEvent) {
    event.preventDefault()
    setTenantError('')
    if (isOidc) {
      const result = validateTenantSelection(tenant, AUTH_CONFIG.organization, AUTH_CONFIG.clientId)
      if (!result.ok) {
        setTenantError(result.message)
        return
      }
    }
    await auth.login(params.get('returnTo') ?? '/dashboard')
  }

  const mark = (
    <span className="login-mark" aria-hidden>
      <AuditOutlined />
    </span>
  )

  return (
    <main className="login-root">
      <div className="login-card">
        <aside className="login-aside">
          <span className="login-aside-ring" aria-hidden />

          <div className="login-aside-top">
            {mark}
            <span className="login-aside-name">对账运营台</span>
          </div>

          <div className="login-aside-main">
            <p className="login-aside-kicker">RECONCILIATION PLATFORM</p>
            <h1 className="login-aside-title">
              让每一笔对账
              <br />
              清晰、可核验、可追溯
            </h1>
            <p className="login-aside-sub">从海量流水到可核验的对账结论,在一个安全统一的工作台中完成。</p>

            <ul className="login-features">
              {FEATURES.map((f) => (
                <li key={f.title}>
                  <span className="login-feature-icon">{f.icon}</span>
                  <span className="login-feature-text">
                    <b>{f.title}</b>
                    <small>{f.desc}</small>
                  </span>
                </li>
              ))}
            </ul>
          </div>

          <div className="login-aside-foot">Reconciliation · Provable by Design</div>
        </aside>

        <section className="login-form">
          <div className="login-form-brand">
            {mark}
            <span>对账运营台</span>
          </div>

          <p className="login-kicker">{isOidc ? 'Casdoor SSO · PKCE' : 'Local · Dev Mode'}</p>
          <h2 className="login-form-title">{isOidc ? '欢迎进入对账运营台' : '进入本地开发模式'}</h2>
          <p className="login-form-sub">
            {isOidc
              ? '输入已开通的组织,继续前往统一身份认证。'
              : '当前为本地免登录模式,一键进入具全部权限的开发身份。'}
          </p>

          <Form layout="vertical" onSubmitCapture={submit} requiredMark={false}>
            <Form.Item
              label="所属组织 / 租户"
              validateStatus={tenantError ? 'error' : undefined}
              help={tenantError || undefined}
            >
              <Input
                value={tenant}
                onChange={(e) => {
                  setTenant(e.target.value)
                  setTenantError('')
                }}
                disabled={auth.redirecting || !isOidc}
                autoComplete="organization"
                spellCheck={false}
                placeholder="例如 recon-platform"
                size="large"
                prefix={<ApartmentOutlined className="login-input-icon" />}
              />
            </Form.Item>

            {isOidc && (
              <div className="login-tenants">
                <span className="login-tenants-label">当前可用组织:</span>
                <button
                  type="button"
                  className={`login-tenant-chip${tenant === AUTH_CONFIG.organization ? ' is-active' : ''}`}
                  onClick={() => {
                    setTenant(AUTH_CONFIG.organization)
                    setTenantError('')
                  }}
                  disabled={auth.redirecting}
                >
                  {AUTH_CONFIG.organization}
                </button>
              </div>
            )}

            {auth.error && <Alert type="error" showIcon message={auth.error} className="login-alert" />}

            <Button
              type="primary"
              size="large"
              block
              htmlType="submit"
              loading={auth.redirecting}
              className="login-submit"
            >
              <span className="login-submit-main">
                {!auth.redirecting && <LoginOutlined />}
                {auth.redirecting ? '正在跳转 Casdoor…' : isOidc ? '使用 Casdoor 登录' : '进入本地开发模式'}
              </span>
              {!auth.redirecting && isOidc && <ArrowRightOutlined className="login-submit-arrow" />}
            </Button>
          </Form>

          <div className="login-secure-note">
            <CheckCircleFilled />
            <span>
              {isOidc
                ? '由 Casdoor 提供统一身份认证,使用 OIDC Authorization Code + PKCE 安全流程。'
                : '本地开发模式免登录,请勿用于生产环境。'}
            </span>
          </div>
        </section>
      </div>

      <p className="login-foot">对账运营台 · 通用自动对账系统 · 内部使用</p>
    </main>
  )
}
