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

/**
 * 内置的服务商预设（地址、控制台链接、说明）。
 *
 * 刻意只给地址、不给模型名：模型上下线很快，写死的名字过一阵就成了
 * "看着对但调不通"的错误答案。模型列表改成实时拉。
 */
export function listProviders() {
  return http.get('/settings/llm/providers')
}

/**
 * 拉取某个服务商的可用模型列表。
 *
 * 比预设想得可靠：既不会过期，也顺带验证了 Key —— 拉不到通常就是 Key 不对。
 * 但并非所有服务商都实现了 GET /models，所以调用方要能退回手动输入。
 */
export function fetchModels(data) {
  return http.post('/settings/llm/models', data, { timeout: 60000 })
}
