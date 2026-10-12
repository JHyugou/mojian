import http from './http'

export function listCharacters(projectId) {
  return http.get(`/projects/${projectId}/characters`)
}

export function createCharacter(projectId, data) {
  return http.post(`/projects/${projectId}/characters`, data)
}

export function updateCharacter(characterId, data) {
  return http.put(`/characters/${characterId}`, data)
}

export function deleteCharacter(characterId) {
  return http.delete(`/characters/${characterId}`)
}

export function draftDialogueExample(characterId, hint) {
  return http.post(`/characters/${characterId}/dialogue-example-drafts`, { hint })
}
// ===== 人物版本节点（起点制：从第 X 章起生效直至下一节点） =====
export function listCharacterNodes(characterId) {
  return http.get(`/characters/${characterId}/nodes`)
}
export function createCharacterNode(characterId, data) {
  return http.post(`/characters/${characterId}/nodes`, data)
}
export function updateCharacterNode(nodeId, data) {
  return http.put(`/character-nodes/${nodeId}`, data)
}
export function deleteCharacterNode(nodeId) {
  return http.delete(`/character-nodes/${nodeId}`)
}
