import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AuditOutlined, LoginOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Form, Input, Typography } from 'antd'
import { AUTH_CONFIG, AUTH_MODE } from '../auth/config'
import { useAuth } from '../auth/AuthContext'
import { validateTenantSelection } from '../auth/tenantSelection'
import { colors } from '../theme/colors'

/**
 * 登录页(AppLayout 之外的独立顶层路由)。对齐 risk-console:oidc 模式确认组织后授权码+PKCE 跳 Casdoor;
 * dev 模式一键进入本地开发身份。复用对账运营台品牌与主题色。
 */
export function LoginPage() {
  const auth = useAuth()
  const [params] = useSearchParams()
  const [tenant, setTenant] = useState(AUTH_CONFIG.organization)
  const [tenantError, setTenantError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    setTenantError('')
    if (AUTH_MODE === 'oidc') {
      const result = validateTenantSelection(tenant, AUTH_CONFIG.organization, AUTH_CONFIG.clientId)
      if (!result.ok) {
        setTenantError(result.message)
        return
      }
    }
    await auth.login(params.get('returnTo') ?? '/dashboard')
  }

  return (
    <main
      style={{
        minHeight: '100dvh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        background: `radial-gradient(1200px 600px at 80% -10%, ${colors.primarySoft}, ${colors.bgLayout})`,
      }}
    >
      <Card style={{ width: '100%', maxWidth: 400 }} styles={{ body: { padding: 28 } }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18 }}>
          <span
            aria-hidden
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 40,
              height: 40,
              borderRadius: 10,
              color: '#fff',
              background: 'linear-gradient(145deg,#315efb,#2546c7)',
              boxShadow: '0 8px 18px rgba(49,94,251,.22)',
            }}
          >
            <AuditOutlined style={{ fontSize: 20 }} />
          </span>
          <Typography.Text strong style={{ fontSize: 18 }}>
            对账运营台
          </Typography.Text>
        </div>

        <Typography.Paragraph type="secondary" style={{ letterSpacing: '.12em', fontSize: 12, marginBottom: 4 }}>
          {AUTH_MODE === 'oidc' ? 'SECURE ACCESS · 统一身份登录' : 'LOCAL · 本地开发模式'}
        </Typography.Paragraph>
        <Typography.Title level={4} style={{ marginTop: 0 }}>
          {AUTH_MODE === 'oidc' ? '使用统一身份登录' : '进入本地开发模式'}
        </Typography.Title>

        <Form layout="vertical" onSubmitCapture={submit}>
          <Form.Item
            label="所属组织"
            validateStatus={tenantError ? 'error' : undefined}
            help={tenantError || `当前可用组织：${AUTH_CONFIG.organization}`}
          >
            <Input
              value={tenant}
              onChange={(e) => {
                setTenant(e.target.value)
                setTenantError('')
              }}
              disabled={auth.redirecting || AUTH_MODE !== 'oidc'}
              autoComplete="organization"
              spellCheck={false}
              placeholder="请输入组织名称"
              size="large"
            />
          </Form.Item>

          {auth.error && <Alert type="error" showIcon message={auth.error} style={{ marginBottom: 12 }} />}

          <Button
            type="primary"
            size="large"
            block
            htmlType="submit"
            loading={auth.redirecting}
            icon={<LoginOutlined />}
          >
            {auth.redirecting
              ? '正在跳转 Casdoor…'
              : AUTH_MODE === 'oidc'
                ? '使用 Casdoor 登录'
                : '进入本地开发模式'}
          </Button>
        </Form>

        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 16, marginBottom: 0 }}>
          统一身份认证 · PKCE 安全登录。系统不存储密码，会话仅在当前标签页有效。
        </Typography.Paragraph>
      </Card>
    </main>
  )
}
