<template>
  <div class="ob">
    <div class="ob-tip mj-muted">
      <b>框选</b>下面的蓝图文字，然后<b>右键</b>即可把它引用到对话里。
    </div>

    <div class="ob-list mj-scroll">
      <div v-for="node in flat" :key="node.id" class="ob-node">
        <button
          class="ob-node-head"
          :style="{ paddingLeft: 8 + node._depth * 12 + 'px' }"
          @click="toggle(node.id)"
        >
          <span class="ob-caret">{{ expanded.has(node.id) ? '▾' : '▸' }}</span>
          <span class="ob-name" :class="{ 'is-volume': node.nodeType === 'VOLUME' }">
            {{ node.name || '未命名' }}
          </span>
          <span class="mj-tag">{{ nodeTypeLabel(node.nodeType) }}</span>
        </button>

        <!-- 可框选区域：右键菜单就挂在这里 -->
        <div
          v-if="expanded.has(node.id)"
          class="ob-node-body mj-fade"
          @contextmenu="onContextMenu($event, node)"
        >
          <div v-if="node.summary" class="ob-text">{{ node.summary }}</div>
          <div v-else class="mj-muted ob-empty">（这个节点还没有详细内容）</div>
        </div>
      </div>

      <div v-if="!flat.length" class="mj-muted ob-empty-block">
        还没有蓝图。<br />在中间和策划 Agent 聊，它会帮你把走向规划成蓝图节点。
      </div>
    </div>

    <!--
      右键菜单。用 Teleport 挂到 body：
      侧栏有 overflow 滚动，菜单如果留在里面会被裁掉一半。
    -->
    <Teleport to="body">
      <div
        v-if="menu"
        class="ob-menu mj-fade"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
        @click.stop
      >
        <div class="ob-menu-preview">「{{ preview(menu.text) }}」</div>
        <button class="ob-menu-item" @click="cite">添加到对话</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  nodes: { type: Array, default: () => [] }
})

const emit = defineEmits(['cite'])

const expanded = ref(new Set())
const menu = ref(null)

const NODE_LABELS = { VOLUME: '卷', ARC: '段', CHAPTER: '章' }
const nodeTypeLabel = (type) => NODE_LABELS[type] || type || '节点'

const flat = computed(() => {
  const out = []
  const walk = (list, depth) => {
    for (const node of list || []) {
      out.push({ ...node, _depth: depth })
      walk(node.children, depth + 1)
    }
  }
  walk(props.nodes, 0)
  return out
})

function toggle(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  expanded.value = next
}

const preview = (text) => (text.length > 26 ? text.slice(0, 26) + '…' : text)

function onContextMenu(event, node) {
  const selection = window.getSelection()
  const text = selection ? selection.toString().trim() : ''
  // 没选中文字就不弹菜单 —— 空引用没有意义，只会让人困惑
  if (!text) return
  event.preventDefault()
  menu.value = {
    x: Math.min(event.clientX, window.innerWidth - 200),
    y: Math.min(event.clientY, window.innerHeight - 100),
    text,
    nodeId: node.id,
    nodeName: node.name
  }
}

function closeMenu() {
  menu.value = null
}

function cite() {
  if (!menu.value) return
  emit('cite', {
    nodeId: menu.value.nodeId,
    nodeName: menu.value.nodeName,
    text: menu.value.text
  })
  // 引用完把选中清掉，否则下一次右键还会带着旧选区
  window.getSelection()?.removeAllRanges()
  closeMenu()
}

onMounted(() => {
  // 点别处、滚动、按 Esc 都关掉菜单 —— 菜单留在屏幕上不走是最烦人的
  document.addEventListener('click', closeMenu)
  document.addEventListener('scroll', closeMenu, true)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('scroll', closeMenu, true)
  document.removeEventListener('keydown', onKeydown)
})

function onKeydown(event) {
  if (event.key === 'Escape') closeMenu()
}
</script>

<style scoped>
.ob {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.ob-tip {
  font-size: 12px;
  line-height: 1.8;
  padding: 4px 4px 10px;
}

.ob-list { flex: 1; min-height: 0; }

.ob-node { margin-bottom: 2px; }

.ob-node-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 8px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out);
}
.ob-node-head:hover { background: var(--color-paper-warm); }

.ob-caret { width: 10px; color: var(--color-muted); font-size: 10px; }

.ob-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ob-name.is-volume {
  font-family: var(--font-serif);
  color: var(--color-brown-deep);
}

/* 蓝图正文：宋体 + 宽松行高。它是要被认真读、被框选的文字 */
.ob-node-body {
  margin: 4px 0 10px 18px;
  padding: 10px 12px;
  border-left: 2px solid var(--color-brown-soft);
  background: var(--color-paper-warm);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  user-select: text;
  cursor: text;
}

.ob-text {
  font-family: var(--font-serif);
  font-size: 13.5px;
  line-height: 1.95;
  white-space: pre-wrap;
  word-break: break-word;
}

.ob-empty { font-size: 12px; }
.ob-empty-block { padding: 20px 10px; font-size: 12px; line-height: 1.9; text-align: center; }

/* 选中高亮：淡淡的棕色，不刺眼 */
.ob-text::selection,
.ob-text *::selection {
  background: rgba(139, 94, 60, 0.22);
}
</style>

<style>
/*
 * 右键菜单刻意不加 scoped：它被 Teleport 到 body，
 * scoped 会给选择器加上本组件的属性，而移到 body 之后那层属性不在了，样式会失效。
 */
.ob-menu {
  position: fixed;
  z-index: 3000;
  min-width: 168px;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.ob-menu-preview {
  padding: 6px 8px 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--color-line);
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.6;
  max-width: 220px;
}

.ob-menu-item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  text-align: left;
  cursor: pointer;
  font-size: 13px;
  color: var(--color-brown-deep);
  transition: background var(--dur) var(--ease-out);
}
.ob-menu-item:hover { background: var(--color-paper-warm); }
</style>
