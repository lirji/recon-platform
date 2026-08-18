import { Navigate, createBrowserRouter } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { DashboardPage } from './pages/DashboardPage'
import { DiscrepanciesPage } from './pages/DiscrepanciesPage'
import { RunsPage } from './pages/RunsPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'discrepancies', element: <DiscrepanciesPage /> },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
])
