import http from './http'

export function createPlanTask(chapterId, instruction) {
  return http.post(`/chapters/${chapterId}/generation-plans`, { instruction })
}

export function createGenerationTask(chapterId, payload) {
  return http.post(`/chapters/${chapterId}/generation-tasks`, payload)
}

export function getGenerationTask(taskId) {
  return http.get(`/generation-tasks/${taskId}`)
}

export function retryGenerationTask(taskId) {
  return http.post(`/generation-tasks/${taskId}/retry`)
}

/**
 * 订阅任务 SSE 事件流。
 * 事件：status（状态变化）/ delta（正文片段）/ task-error（失败原因）/ end（终结）。
 * 返回 EventSource，调用方负责 close()。
 */
export function subscribeTask(taskId, handlers) {
  const source = new EventSource(`/api/generation-tasks/${taskId}/events`)
  source.addEventListener('status', (e) => handlers.onStatus?.(e.data))
  source.addEventListener('delta', (e) => handlers.onDelta?.(e.data))
  source.addEventListener('reasoning', (e) => handlers.onReasoning?.(e.data))
  source.addEventListener('task-error', (e) => handlers.onError?.(e.data))
  source.addEventListener('end', (e) => {
    handlers.onEnd?.(e.data)
    source.close()
  })
  return source
}
