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
