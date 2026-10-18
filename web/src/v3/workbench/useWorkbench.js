import { computed, reactive, ref } from 'vue'
import { listChapters, getChapter } from '../../api/chapter'
import { listCharacters } from '../../api/character'
import { listSettings } from '../../api/setting'
import { getOutlineTree } from '../../api/outline'
import { getContextReport } from '../../api/agent'

/**
 * 工作台的共享数据层（v3）。
 *
 * <p>把"取一次、两个面板都要用"的东西收在这里：章节、资料、上下文报表。
 * 之前这些散在 WorkbenchPage 里，切换项目/采纳草稿时会整页重拉、还会把当前章重置，
 * 这里用请求序号 + 保当前章的方式收口。
 */
export function useWorkbench(projectIdRef) {
  const chapters = ref([])
  const activeChapter = ref(null)
  const outlineTree = ref([])
  const characters = ref([])
  const settings = ref([])
  const reports = ref({ PLANNER: null, WRITER: null })
  const loading = ref(false)
  const error = ref('')
  const citations = ref([])

  let loadSeq = 0

  async function loadAll() {
    const projectId = projectIdRef.value
    if (!projectId) return
    const seq = ++loadSeq
    loading.value = true
    error.value = ''

    const [chapterResult, characterResult, settingResult, outlineResult] = await Promise.allSettled([
      listChapters(projectId),
      listCharacters(projectId),
      listSettings(projectId),
      getOutlineTree(projectId)
    ])
    if (seq !== loadSeq) return

    if (chapterResult.status === 'fulfilled') {
      const next = chapterResult.value || []
      const currentId = activeChapter.value?.id
      const kept = currentId ? next.find((c) => String(c.id) === String(currentId)) : null
      chapters.value = next
      activeChapter.value = kept || next[next.length - 1] || null
    } else {
      error.value = chapterResult.reason?.message || '读取章节失败'
    }
    if (characterResult.status === 'fulfilled') characters.value = characterResult.value || []
    if (settingResult.status === 'fulfilled') settings.value = settingResult.value || []
    if (outlineResult.status === 'fulfilled') outlineTree.value = outlineResult.value || []

    loading.value = false
    loadReports()
  }

  async function loadReports() {
    const projectId = projectIdRef.value
    if (!projectId) return
    const [planner, writer] = await Promise.allSettled([
      getContextReport(projectId, 'PLANNER'),
      getContextReport(projectId, 'WRITER')
    ])
    reports.value = {
      PLANNER: planner.status === 'fulfilled' ? planner.value : null,
      WRITER: writer.status === 'fulfilled' ? writer.value : null
    }
  }

  const flatOutline = computed(() => {
    const out = []
    const walk = (nodes, depth = 0) => {
      for (const node of nodes || []) {
        out.push({ ...node, _depth: depth })
        walk(node.children, depth + 1)
      }
    }
    walk(outlineTree.value)
    return out
  })

  const activeBlueprint = computed(() => {
    const nodeId = activeChapter.value?.outlineNodeId
    if (!nodeId) return null
    return flatOutline.value.find((n) => String(n.id) === String(nodeId)) || null
  })

  const counts = computed(() => ({
    chapters: chapters.value.length,
    characters: characters.value.length,
    settings: settings.value.length,
    outline: flatOutline.value.length
  }))

  function selectChapter(chapter) {
    activeChapter.value = chapter
  }

  async function refreshChapter() {
    if (!activeChapter.value) return
    try {
      const detail = await getChapter(activeChapter.value.id)
      activeChapter.value = { ...activeChapter.value, ...detail }
      const index = chapters.value.findIndex((c) => String(c.id) === String(detail.id))
      if (index >= 0) chapters.value[index] = { ...chapters.value[index], ...detail }
    } catch {
      /* 单章刷新失败不阻断写作 */
    }
  }

  function addCitation(citation) {
    const key = citation.targetId ?? citation.nodeId
    if (citations.value.some((c) => (c.targetId ?? c.nodeId) === key && c.text === citation.text)) return
    citations.value = [...citations.value, citation]
  }
  function removeCitation(index) {
    citations.value = citations.value.filter((_, i) => i !== index)
  }
  function clearCitations() {
    citations.value = []
  }

  return reactive({
    chapters,
    activeChapter,
    outlineTree,
    characters,
    settings,
    reports,
    loading,
    error,
    citations,
    flatOutline,
    activeBlueprint,
    counts,
    loadAll,
    loadReports,
    selectChapter,
    refreshChapter,
    addCitation,
    removeCitation,
    clearCitations
  })
}
