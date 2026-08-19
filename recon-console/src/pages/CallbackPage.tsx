import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'
import { useAuth } from '../auth/AuthContext'

/**
 * Casdoor 授权码回调页(/auth/callback,AppLayout 之外)。换 token → 跳回登录前深链(returnTo)。
 * useRef once-guard 防 React.StrictMode 开发期双挂载重复消费一次性 code。
 */
export function CallbackPage() {
  const { completeLogin } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  const started = useRef(false)

  useEffect(() => {
    // once-guard(ref 在 StrictMode 双挂载同实例间保留)保证 code 只消费一次;
    // 不用 active/cleanup 标志——否则 StrictMode 首个 setup 的 cleanup 会把成功后的 navigate 吃掉。
    if (started.current) return
    started.current = true
    completeLogin()
      .then((path) => navigate(path, { replace: true }))
      .catch((cause) => setError(cause instanceof Error ? cause.message : '登录回调处理失败'))
  }, [completeLogin, navigate])

  return (
    <main
      style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}
    >
      {error ? (
        <Result
          status="warning"
          title="登录未完成"
          subTitle={error}
          extra={
            <Button type="primary" onClick={() => navigate('/login', { replace: true })}>
              返回登录
            </Button>
          }
        />
      ) : (
        <Spin size="large" tip="正在完成登录…">
          <div style={{ padding: 24 }} />
        </Spin>
      )}
    </main>
  )
}
