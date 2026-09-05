/** 从 URL 取出非空筛选键(忽略分页)。 */
export function pickSearchParams(
  params: URLSearchParams,
  keys: readonly string[],
): Record<string, string> {
  const seeded: Record<string, string> = {}
  for (const key of keys) {
    const value = params.get(key)
    if (value) seeded[key] = value
  }
  return seeded
}

/** 把当前筛选写成 URLSearchParams;空值省略,分页不进 URL。 */
export function toSearchParams(values: Record<string, string | undefined | null>): URLSearchParams {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value) params.set(key, value)
  }
  return params
}
