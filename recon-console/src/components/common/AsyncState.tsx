import { Alert, Button, Empty, Skeleton } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'

export function PageSkeleton() {
  return (
    <div aria-label="正在加载" className="page-skeleton">
      <Skeleton active paragraph={{ rows: 3 }} />
      <Skeleton active paragraph={{ rows: 6 }} />
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <Alert
      type="error"
      showIcon
      message="页面加载失败"
      description={message}
      action={
        <Button icon={<ReloadOutlined />} onClick={onRetry}>
          重试
        </Button>
      }
    />
  )
}

export function EmptyState({ filtered = false, onReset }: { filtered?: boolean; onReset?: () => void }) {
  return (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={filtered ? '当前筛选条件下没有结果' : '还没有可展示的数据'}
    >
      {filtered && onReset && <Button onClick={onReset}>清除筛选</Button>}
    </Empty>
  )
}
