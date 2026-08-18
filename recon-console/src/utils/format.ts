export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatMinor(value: string | number, currency?: string | null): string {
  try {
    const amount = BigInt(value)
    const formatted = new Intl.NumberFormat('zh-CN').format(amount)
    return currency ? `${currency} ${formatted}` : formatted
  } catch {
    return currency ? `${currency} ${value}` : String(value)
  }
}

export function formatCount(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}
