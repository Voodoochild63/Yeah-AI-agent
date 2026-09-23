/**
 * 零成本 Google Maps 搜索链接。
 * 用店名 + 城市（默认 Sydney）拼 query，不需要 API Key。
 */
export function getGoogleMapsUrl(name: string, extraLocation: string = 'Sydney'): string {
  const trimmedName = name?.trim()
  if (!trimmedName) return '#'

  const extra = extraLocation.trim()
  const query = extra ? `${trimmedName} ${extra}` : trimmedName
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`
}

/**
 * 零成本 Google Maps 驾车路线。
 * 起点、终点和途经点都带上 Sydney，waypoints 用 | 分隔且各自编码。
 * 少于 2 个地点时返回 null。
 */
export function getGoogleMapsDirectionsUrl(names: string[], extraLocation: string = 'Sydney'): string | null {
  const stops = names.map((name) => name?.trim()).filter((name): name is string => Boolean(name))
  if (stops.length < 2) return null

  const withCity = (name: string) => {
    const extra = extraLocation.trim()
    return extra ? `${name} ${extra}` : name
  }

  const origin = encodeURIComponent(withCity(stops[0]!))
  const destination = encodeURIComponent(withCity(stops[stops.length - 1]!))
  const waypoints = stops
    .slice(1, -1)
    .map((name) => encodeURIComponent(withCity(name)))
    .join('|')

  const url = `https://www.google.com/maps/dir/?api=1&origin=${origin}&destination=${destination}&travelmode=driving`
  return waypoints ? `${url}&waypoints=${waypoints}` : url
}

/** 零成本 Google Maps 周边停车检索，不指定具体车库。 */
export function getGoogleMapsParkingUrl(query: string): string {
  const q = query?.trim()
  if (!q) return '#'
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`
}
