import http from './http'

export function listChapters(projectId) {
  return http.get(`/projects/${projectId}/chapters`)
}

export function createChapter(projectId, data) {
  return http.post(`/projects/${projectId}/chapters`, data)
}

export function getChapter(chapterId) {
  return http.get(`/chapters/${chapterId}`)
}

export function saveChapter(chapterId, data) {
  return http.put(`/chapters/${chapterId}`, data)
}

export function deleteChapter(chapterId) {
  return http.delete(`/chapters/${chapterId}`)
}

export function getMemory(chapterId) {
  return http.get(`/chapters/${chapterId}/memory`)
}

export function saveMemory(chapterId, data) {
  return http.put(`/chapters/${chapterId}/memory`, data)
}

export function listVersions(chapterId) {
  return http.get(`/chapters/${chapterId}/versions`)
}

export function getVersion(versionId) {
  return http.get(`/chapter-versions/${versionId}`)
}

export function compareVersions(baseVersionId, targetVersionId) {
  return http.get('/chapter-versions/compare', { params: { baseVersionId, targetVersionId } })
}

export function rollbackChapter(chapterId, versionId) {
  return http.post(`/chapters/${chapterId}/rollback`, { versionId })
}
