import http from './http'

/** 项目级导出（sections 缺省全选：characters, settings, outline, chapters） */
export function exportProject(projectId, sections) {
  return http.get(`/projects/${projectId}/export`, { params: { sections: sections.join(',') } })
}

/** 导入整本项目：创建全新项目承载 */
export function importProject(payload) {
  return http.post('/projects/import', payload)
}

/** 素材级：人物设定集（含演变节点时间线） */
export function exportCharacters(projectId, ids) {
  return http.post(`/projects/${projectId}/characters/export`, ids ? { ids } : {})
}
export function importCharacters(projectId, payload) {
  return http.post(`/projects/${projectId}/characters/import`, payload)
}

/** 素材级：世界观设定 */
export function exportSettings(projectId, ids) {
  return http.post(`/projects/${projectId}/settings/export`, ids ? { ids } : {})
}
export function importSettings(projectId, payload) {
  return http.post(`/projects/${projectId}/settings/import`, payload)
}
