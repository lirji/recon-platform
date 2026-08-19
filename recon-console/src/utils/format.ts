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

// 判定最小单位金额串是否非零。用 BigInt 解析,禁转 number 防精度损失;非法/空串回退为「非零」(与 formatMinor 一致的保守防御)。
export function isNonZeroMinor(value: string | null | undefined): boolean {
  if (value == null) return false
  try {
    return BigInt(value) !== 0n
  } catch {
    return value.trim() !== '' && value.trim() !== '0'
  }
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}
