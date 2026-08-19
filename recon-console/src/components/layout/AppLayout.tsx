import { useState } from 'react'
import { AlertOutlined, AuditOutlined, DashboardOutlined, MenuFoldOutlined, MenuOutlined, MenuUnfoldOutlined, SettingOutlined, SolutionOutlined } from '@ant-design/icons'
import { Breadcrumb, Button, Drawer, Grid, Layout, Menu, Space } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { colors } from '../../theme/colors'
import { UserMenu } from './UserMenu'

const navigation = [
  { key: '/dashboard', label: '工作台', icon: <DashboardOutlined /> },
  { key: '/runs', label: '运行管理', icon: <AuditOutlined /> },
  { key: '/discrepancies', label: '差异处理', icon: <AlertOutlined /> },
  { key: '/reversal-approvals', label: '冲正审批', icon: <SolutionOutlined /> },
  { key: '/scenarios', label: '场景管理', icon: <SettingOutlined /> },
]

export function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const current = navigation.find((item) => location.pathname.startsWith(item.key))

  const menu = (afterClick?: () => void) => (
    <Menu
      mode="inline"
      selectedKeys={[current?.key || '/dashboard']}
      items={navigation}
      onClick={({ key }) => {
        navigate(key)
        afterClick?.()
      }}
      style={{ borderInlineEnd: 0 }}
    />
  )

  const brand = (
    <div className="brand" aria-label="对账运营台">
      <span className="brand-mark"><AuditOutlined /></span>
      {!collapsed && <span>对账运营台</span>}
    </div>
  )

  return (
    <Layout className="app-shell">
      {!isMobile && (
        <Layout.Sider
          theme="light"
          width={224}
          collapsedWidth={72}
          collapsed={collapsed}
          trigger={null}
          className="app-sider"
        >
          {brand}
          {menu()}
        </Layout.Sider>
      )}

      <Layout>
        <Layout.Header className="app-header">
          <Space>
            <Button
              type="text"
              aria-label={isMobile ? '打开菜单' : collapsed ? '展开菜单' : '收起菜单'}
              icon={isMobile ? <MenuOutlined /> : collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => (isMobile ? setDrawerOpen(true) : setCollapsed((value) => !value))}
            />
            <Breadcrumb items={[{ title: '运营管理' }, { title: current?.label || '工作台' }]} />
          </Space>
          <UserMenu />
        </Layout.Header>
        <Layout.Content>
          <main className="app-content">
            <Outlet />
          </main>
        </Layout.Content>
      </Layout>

      <Drawer
        placement="left"
        width={240}
        open={isMobile && drawerOpen}
        onClose={() => setDrawerOpen(false)}
        styles={{ body: { padding: 0 } }}
        title={<Space><AuditOutlined style={{ color: colors.primary }} />对账运营台</Space>}
      >
        {menu(() => setDrawerOpen(false))}
      </Drawer>
    </Layout>
  )
}
