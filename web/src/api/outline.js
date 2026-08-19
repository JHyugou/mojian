import http from './http'

export function getOutlineTree(projectId) {
  return http.get(`/projects/${projectId}/outline`)
}

export function createOutlineNode(projectId, data) {
  return http.post(`/projects/${projectId}/outline/nodes`, data)
}

export function updateOutlineNode(nodeId, data) {
  return http.put(`/outline/nodes/${nodeId}`, data)
}

export function deleteOutlineNode(nodeId) {
  return http.delete(`/outline/nodes/${nodeId}`)
}
