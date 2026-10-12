import http from './http'

/** 成书（FR-020）：全书装配，供阅读与导出 */
export function getBook(projectId) {
  return http.get(`/projects/${projectId}/book`)
}
