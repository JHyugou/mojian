import http from './http'

/*
 * Agent 相关接口。
 *
 * 注意超时：策划对话要几十秒、写作要两三分钟，而 http 默认只有 15 秒。
 * 所以凡是会真的调模型的接口，都必须显式放宽 timeout —— 否则请求会在
 * 模型还在思考时被前端掐断，表现为"莫名其妙失败"。
 */
const PLANNER_TIMEOUT = 10 * 60 * 1000
const WRITER_TIMEOUT = 30 * 60 * 1000

// ==================== 策划对话 ====================

/** conversationId 传 null 表示新开一段对话 */
export function plannerChat(projectId, conversationId, message) {
  return http.post('/agent/planner/chat', { projectId, conversationId, message },
    { timeout: PLANNER_TIMEOUT })
}

/** 该会话已给出的剧情走向方案（前端渲染成卡片） */
export function listPlanOptions(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/plan-options`)
}

/** 用户选定某条走向；选定后它不再作为"待选方案"注入下一轮 */
export function selectPlanOption(conversationId, optionIndex) {
  return http.post(`/agent/conversations/${conversationId}/plan-options/${optionIndex}/select`)
}

// ==================== 会话（项目与对话解耦） ====================

/**
 * 某项目下某个角色的会话列表（最近用过的在前）。
 *
 * 一个项目可以有很多段对话：对某轮不满意就新开一段，也可以回去续之前那段。
 */
export function listConversations(projectId, role = 'PLANNER') {
  return http.get('/agent/conversations', { params: { projectId, role } })
}

/** 一段会话的"人话"历史，用于界面上回看 */
export function getConversationMessages(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/messages`)
}

// ==================== 草稿（人工确认） ====================

/** pendingOnly=true 就是"待确认"列表 */
export function listDrafts(conversationId, pendingOnly = false) {
  return http.get(`/agent/conversations/${conversationId}/drafts`, { params: { pendingOnly } })
}

/** 确认草稿 —— 到这一步才真正写进人物/设定/蓝图表 */
export function acceptDraft(draftId) {
  return http.post(`/agent/drafts/${draftId}/accept`)
}

export function rejectDraft(draftId) {
  return http.post(`/agent/drafts/${draftId}/reject`)
}

// ==================== 写作 ====================

/**
 * 本书的写作会话与历史。
 *
 * 写作会话是整本书一个、跨章存续的 —— 副 Agent 靠它记得住你此前说过的写作偏好。
 */
export function getWriterConversation(projectId) {
  return http.get(`/agent/writer/projects/${projectId}/conversation`)
}

/** 清空写作会话的历史（保留会话）—— 让它忘掉此前说过的话 */
export function clearWriterHistory(projectId) {
  return http.delete(`/agent/writer/projects/${projectId}/conversation/messages`)
}

/** 启动写作任务，立刻返回 { taskId }，正文通过 SSE 推送 */
export function startWritingTask(chapterId, instruction) {
  return http.post(`/agent/writer/chapters/${chapterId}/tasks`, { instruction })
}

/** 同步写作（调试用，会一直等到写完） */
export function runWriterSync(chapterId, instruction) {
  return http.post(`/agent/writer/chapters/${chapterId}/write`, { instruction },
    { timeout: WRITER_TIMEOUT })
}

/** 设置本章写作要求（风格、人称、篇幅、禁忌） */
export function setWritingRequirement(chapterId, requirement) {
  return http.put(`/agent/chapters/${chapterId}/writing-requirement`, { requirement })
}

/**
 * 订阅写作任务的 SSE 事件流。
 *
 * 事件：status / progress / delta（正文增量）/ reasoning /
 *       tool-call / tool-result / result / task-error / end
 *
 * 返回 EventSource，调用方负责 close()。
 */
export function subscribeWritingTask(taskId, handlers) {
  const source = new EventSource(`/api/agent/writer/tasks/${taskId}/events`)
  source.addEventListener('status', (e) => handlers.onStatus?.(e.data))
  source.addEventListener('progress', (e) => handlers.onProgress?.(e.data))
  source.addEventListener('delta', (e) => handlers.onDelta?.(e.data))
  source.addEventListener('reasoning', (e) => handlers.onReasoning?.(e.data))
  source.addEventListener('tool-call', (e) => handlers.onToolCall?.(e.data))
  source.addEventListener('tool-result', (e) => handlers.onToolResult?.(e.data))
  source.addEventListener('result', (e) => handlers.onResult?.(e.data))
  source.addEventListener('task-error', (e) => handlers.onError?.(e.data))
  source.addEventListener('end', (e) => {
    handlers.onEnd?.(e.data)
    source.close()
  })
  return source
}

// ==================== 可观测性 ====================

/** 上下文装填情况 —— 界面上的"上下文用量"就用它 */
export function getContextReport(projectId, role = 'PLANNER') {
  return http.get(`/agent/projects/${projectId}/context-report`, { params: { role } })
}

export function listAgentProfiles() {
  return http.get('/agent/profiles')
}

export function updateAgentProfile(role, data) {
  return http.put(`/agent/profiles/${role}`, data)
}

export function listAgentTools() {
  return http.get('/agent/tools')
}
