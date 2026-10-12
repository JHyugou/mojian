import http from './http'

/**
 * 模型接入设置（FR-019）。所有接口的 apiKey 均为掩码或不存在，
 * 保存时的 apiKey 语义：null=沿用已存；''=被服务端拒绝；非空=替换。
 */
export function getAiStatus() {
  return http.get('/settings/llm')
}

export function saveAiConfig(data) {
  return http.put('/settings/llm', data)
}

export function testAiConnection(data) {
  return http.post('/settings/llm/test', data)
}

export function resetAiConfig() {
  return http.delete('/settings/llm')
}
