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

/** conversationId 传 null 表示新开一段对话；signal 用于中止（用户点"停下"）；images 为 data URL；autoApprove 告诉后端当前采纳模式 */
export function plannerChat(projectId, conversationId, message, signal, runId, images = [], autoApprove = false) {
  return http.post('/agent/planner/chat', { projectId, conversationId, message, runId, images, autoApprove },
    { timeout: PLANNER_TIMEOUT, signal })
}

/** 该会话已给出的剧情走向方案（前端渲染成卡片） */
export function listPlanOptions(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/plan-options`)
}

/** 用户选定某条走向；选定后它不再作为"待选方案"注入下一轮 */
export function selectPlanOption(conversationId, optionIndex) {
  return http.post(`/agent/conversations/${conversationId}/plan-options/${optionIndex}/select`)
}

/** 收起当前这一批故事走向（用户不选、改用自己的说法时） */
export function dismissPlanOptions(conversationId) {
  return http.post(`/agent/conversations/${conversationId}/plan-options/dismiss`)
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

/** 全部项目、全部角色的会话（用于深链校验：会话 id 到底属于哪个项目） */
export function listAllConversations() {
  return http.get('/agent/conversations')
}

/** 一段会话的"人话"历史，用于界面上回看 */
export function getConversationMessages(conversationId) {
  return http.get(`/agent/conversations/${conversationId}/messages`)
}

/** 给会话改名 */
export function renameConversation(conversationId, title) {
  return http.put(`/agent/conversations/${conversationId}/title`, { title })
}

/** 删除一段会话（连同它的消息） */
export function deleteConversation(conversationId) {
  return http.delete(`/agent/conversations/${conversationId}`)
}

// ==================== 草稿（人工确认） ====================

/** pendingOnly=true 就是"待确认"列表 */
export function listDrafts(conversationId, pendingOnly = false) {
  return http.get(`/agent/conversations/${conversationId}/drafts`, { params: { pendingOnly } })
}

/** 本书的待确认草稿（不看是哪段会话起草的）。 */
export function listProjectDrafts(projectId, pendingOnly = true) {
  return http.get(`/agent/projects/${projectId}/drafts`, { params: { pendingOnly } })
}

/** 全部采纳。返回 { total, accepted, failed } —— 单条失败不影响其余 */
export function acceptAllDrafts(projectId) {
  return http.post(`/agent/projects/${projectId}/drafts/accept-all`)
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

/** 策划 Agent 给写作 Agent 的指令（含已执行），写作面板用来显示两个指令来源 */
export function getWriterInstructions(projectId) {
  return http.get(`/agent/writer/projects/${projectId}/instructions`)
}

export function getChapterSupplements(chapterId) {
  return http.get(`/agent/writer/chapters/${chapterId}/supplements`)
}

/** 写作 Agent 的章节删除申请（PENDING 需要作者确认；APPROVED 已删） */
export function getChapterDeleteRequests(projectId) {
  return http.get(`/agent/writer/projects/${projectId}/chapter-delete-requests`)
}

export function approveChapterDeleteRequest(requestId) {
  return http.post(`/agent/writer/chapter-delete-requests/${requestId}/approve`)
}

export function rejectChapterDeleteRequest(requestId) {
  return http.post(`/agent/writer/chapter-delete-requests/${requestId}/reject`)
}

/** 清空写作会话的历史（保留会话）—— 让它忘掉此前说过的话 */
export function clearWriterHistory(projectId) {
  return http.delete(`/agent/writer/projects/${projectId}/conversation/messages`)
}

/** 启动写作任务，立刻返回 { taskId }，正文通过 SSE 推送 */
export function startWritingTask(chapterId, instruction, autoApproveDelete = false, supplementId = null) {
  return http.post(`/agent/writer/chapters/${chapterId}/tasks`, {
    instruction,
    autoApproveDelete,
    supplementId
  })
}

/** 该章当前正在跑的写作任务；用于切走再切回时重新挂载，而不是取消重来 */
export function getActiveWritingTask(chapterId) {
  return http.get(`/agent/writer/chapters/${chapterId}/active-task`)
}

/**
 * 终止一个正在跑的写作任务。
 *
 * 光关掉前端的 EventSource 是不够的 —— 后端线程还在跑、模型还在生成、钱还在花。
 * 必须显式告诉后端停。
 */
export function cancelWritingTask(taskId) {
  return http.post(`/agent/writer/tasks/${taskId}/cancel`)
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
 * 事件：
 *   status / progress / reasoning / tool-call / tool-result / result / task-error / end
 *   delta   —— 模型<b>说</b>的话（对话流里的回答）
 *   content —— 模型<b>写</b>的正文增量。正文装在工具参数里，所以单独一条通道
 *   *-snapshot —— 订阅时服务端回放的"当前全文"（重连时用，前端要替换而不是追加）
 *
 * 返回 EventSource，调用方负责 close()。
 */
export function subscribeWritingTask(taskId, handlers) {
  const source = new EventSource(`/api/agent/writer/tasks/${taskId}/events`)
  source.addEventListener('status', (e) => handlers.onStatus?.(e.data))
  source.addEventListener('progress', (e) => handlers.onProgress?.(e.data))
  source.addEventListener('delta', (e) => handlers.onDelta?.(e.data))
  source.addEventListener('delta-snapshot', (e) => handlers.onDeltaSnapshot?.(e.data))
  source.addEventListener('content', (e) => handlers.onContent?.(e.data))
  source.addEventListener('content-snapshot', (e) => handlers.onContentSnapshot?.(e.data))
  source.addEventListener('reasoning', (e) => handlers.onReasoning?.(e.data))
  source.addEventListener('reasoning-snapshot', (e) => handlers.onReasoningSnapshot?.(e.data))
  source.addEventListener('tool-call', (e) => handlers.onToolCall?.(e.data))
  source.addEventListener('tool-result', (e) => handlers.onToolResult?.(e.data))
  source.addEventListener('result', (e) => handlers.onResult?.(e.data))
  source.addEventListener('task-error', (e) => handlers.onError?.(e.data))
  source.addEventListener('end', (e) => {
    handlers.onEnd?.(e.data)
    source.close()
  })
  // 连接异常：EventSource 会自动重连，真正关闭时才让界面进入失败态
  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      handlers.onError?.('事件流连接已断开，请刷新后重试')
    }
  }
  return source
}

// ==================== 可观测性 ====================

/** 上下文装填情况 —— 界面上的"上下文用量"就用它 */
export function getContextReport(projectId, role = 'PLANNER', chapterId = null) {
  return http.get(`/agent/projects/${projectId}/context-report`, {
    params: { role, chapterId: chapterId || undefined }
  })
}

export function listAgentProfiles() {
  return http.get('/agent/profiles')
}

export function updateAgentProfile(role, data) {
  return http.put(`/agent/profiles/${role}`, data)
}

export function listAgentTools(role) {
  return http.get('/agent/tools', { params: role ? { role } : {} })
}

/** 用量与花销（美元）：最近调用流水 + 近 N 天汇总 */
export function getAgentUsage(limit = 20, days = 30) {
  return http.get('/agent/usage', { params: { limit, days } })
}

/**
 * 叫停一段正在跑的策划对话。
 *
 * 前端断开连接是"我看不见了"，不是"现在停" ——
 * 服务端线程还在跑、模型还在生成、钱还在花。必须显式告诉后端。
 */
export function cancelPlannerRun(runId) {
  return http.post(`/agent/planner/runs/${runId}/cancel`)
}

/**
 * 对话回滚：从第 keepUserMessages+1 条用户消息开始，把它和后面的一切从上下文里抹掉。
 *
 * 前端手上只有渲染出来的数组，下标和数据库行号不是一回事（中间夹着工具调用），
 * 所以用"第几条用户消息"这个两边都能算的口径。
 */
export function rollbackConversation(conversationId, keepUserMessages, rollbackDatabase = true) {
  return http.post(`/agent/conversations/${conversationId}/rollback`,
    { keepUserMessages, rollbackDatabase })
}