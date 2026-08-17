import http from './http'

export function listProjects() {
  return http.get('/projects')
}

export function getProject(id) {
  return http.get(`/projects/${id}`)
}

export function createProject(data) {
  return http.post('/projects', data)
}

export function updateProject(id, data) {
  return http.put(`/projects/${id}`, data)
}

export function deleteProject(id) {
  return http.delete(`/projects/${id}`)
}
