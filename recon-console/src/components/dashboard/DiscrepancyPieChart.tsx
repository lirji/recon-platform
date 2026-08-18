import ReactEChartsCore from 'echarts-for-react/lib/core'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { KeyCount } from '../../api/types'
import { colors } from '../../theme/colors'
import { discrepancyTypeLabels } from '../common/StatusTag'

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

export function DiscrepancyPieChart({ data }: { data: KeyCount[] }) {
  const option = {
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c} 条（{d}%）' },
    legend: { type: 'scroll', bottom: 0, textStyle: { color: colors.textSecondary } },
    color: [colors.primary, colors.warning, colors.error, colors.success, '#7A5AF8', '#06AED4', '#F79009'],
    series: [
      {
        name: '差异类型',
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['50%', '43%'],
        avoidLabelOverlap: true,
        label: { show: false },
        data: data.map((item) => ({ name: discrepancyTypeLabels[item.key] || item.key, value: item.count })),
      },
    ],
  }

  return <ReactEChartsCore echarts={echarts} option={option} style={{ height: 320 }} aria-label="差异类型构成图" />
}
