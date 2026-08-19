import http from './http'

export function listSettings(projectId, keyword) {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''
  return http.get(`/projects/${projectId}/settings${query}`)
}

export function createSetting(projectId, data) {
  return http.post(`/projects/${projectId}/settings`, data)
}

export function updateSetting(settingId, data) {
  return http.put(`/settings/${settingId}`, data)
}

export function deleteSetting(settingId) {
  return http.delete(`/settings/${settingId}`)
}
