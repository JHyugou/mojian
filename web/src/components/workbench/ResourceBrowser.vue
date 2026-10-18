<template>
  <div class="rb">
    <div class="rb-tip mj-muted">
      <b>框选</b>下面的文字，然后<b>右键</b>即可把它引用到对话里。
    </div>

    <div class="rb-list mj-scroll">
      <div v-for="item in flat" :key="item.key" class="rb-item">
        <button
          class="rb-item-head"
          :style="{ paddingLeft: 8 + item._depth * 12 + 'px' }"
          @click="toggle(item.key)"
        >
          <span class="rb-caret">{{ expanded.has(item.key) ? '▾' : '▸' }}</span>
          <span class="rb-title" :class="{ 'is-group': item._depth === 0 && hasChildren(item) }">
            {{ item.title || '未命名' }}
          </span>
          <span v-if="item.tag" class="mj-tag">{{ item.tag }}</span>
        </button>

        <!-- 可框选区域：右键菜单就挂在这里 -->
        <div
          v-if="expanded.has(item.key)"
          class="rb-body mj-fade"
          @contextmenu="onContextMenu($event, item)"
        >
          <div v-if="item.body" class="rb-text">{{ item.body }}</div>
          <div v-else class="mj-muted rb-empty">（这一条还没有详细内容）</div>
        </div>
      </div>

      <div v-if="!flat.length" class="mj-muted rb-empty-block">
        {{ emptyHint }}
      </div>
    </div>

    <!--
      右键菜单。用 Teleport 挂到 body：
      侧栏有 overflow 滚动，菜单如果留在里面会被裁掉一半。
    -->
    <Teleport to="body">
      <div
        v-if="menu"
        class="rb-menu mj-fade"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
        @click.stop
      >
        <div class="rb-menu-preview">「{{ preview(menu.text) }}」</div>
        <button class="rb-menu-item" @click="cite">添加到对话</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 通用资源浏览器：人物 / 设定 / 蓝图都用它。
 *
 * 三者结构上是一回事 —— 一个标题、一个标签、一段正文，可能带子项。
 * 与其写三个几乎一样的组件，不如把差异留在调用方（由它把数据转成统一的条目）。
 */
const props = defineProps({
  /** [{ id, title, tag, body, children }] */
  items: { type: Array, default: () => [] },
  /** 所属资源的种类名，用于引用时标明出处（人物 / 设定 / 蓝图节点） */
  kind: { type: String, default: '资料' },
  emptyHint: { type: String, default: '这里还没有内容。' }
})

const emit = defineEmits(['cite'])

const expanded = ref(new Set())
const menu = ref(null)

const flat = computed(() => {
  const out = []
  const walk = (list, depth) => {
    for (const item of list || []) {
      out.push({ ...item, key: `${depth}-${item.id}`, _depth: depth })
      walk(item.children, depth + 1)
    }
  }
  walk(props.items, 0)
  return out
})

function hasChildren(item) {
  return Array.isArray(item.children) && item.children.length > 0
}

function toggle(key) {
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expanded.value = next
}

const preview = (text) => (text.length > 26 ? text.slice(0, 26) + '…' : text)

function onContextMenu(event, item) {
  const selection = window.getSelection()
  const text = selection ? selection.toString().trim() : ''
  // 没选中文字就不弹菜单 —— 空引用没有意义，只会让人困惑
  if (!text) return
  event.preventDefault()
  menu.value = {
    x: Math.min(event.clientX, window.innerWidth - 220),
    y: Math.min(event.clientY, window.innerHeight - 100),
    text,
    targetId: item.id,
    targetName: item.title,
    kind: props.kind
  }
}

function closeMenu() {
  menu.value = null
}

function cite() {
  if (!menu.value) return
  emit('cite', {
    targetId: menu.value.targetId,
    targetName: menu.value.targetName,
    kind: menu.value.kind,
    text: menu.value.text,
    // 记录 ID 一起带上：名称会重名，ID 不会。
    // 模型拿到它就能直接调用 update_*，不必再查一遍
    locator: menu.value.targetId ? `(记录ID ${menu.value.targetId})` : ''
  })
  // 引用完把选中清掉，否则下一次右键还会带着旧选区
  window.getSelection()?.removeAllRanges()
  closeMenu()
}

function onKeydown(event) {
  if (event.key === 'Escape') closeMenu()
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
</script>

<style scoped>
.rb { display: flex; flex-direction: column; min-height: 0; }

.rb-tip { font-size: 12px; line-height: 1.8; padding: 4px 4px 10px; }

.rb-list { flex: 1; min-height: 0; }

.rb-item { margin-bottom: 2px; }

.rb-item-head {
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
.rb-item-head:hover { background: var(--color-paper-warm); }

.rb-caret { width: 10px; color: var(--color-muted); font-size: 10px; }

.rb-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 顶层分组（卷）用宋体与深棕，和普通条目区分开 */
.rb-title.is-group {
  font-family: var(--font-serif);
  color: var(--color-brown-deep);
}

/* 正文：宋体 + 宽松行高。它是要被认真读、被框选的文字 */
.rb-body {
  margin: 4px 0 10px 18px;
  padding: 10px 12px;
  border-left: 2px solid var(--color-brown-soft);
  background: var(--color-paper-warm);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  user-select: text;
  cursor: text;
}

.rb-text {
  font-family: var(--font-serif);
  font-size: 13.5px;
  line-height: 1.95;
  white-space: pre-wrap;
  word-break: break-word;
}

.rb-empty { font-size: 12px; }
.rb-empty-block { padding: 20px 10px; font-size: 12px; line-height: 1.9; text-align: center; }

/* 选中高亮：淡淡的棕色，不刺眼 */
.rb-text::selection,
.rb-text *::selection {
  background: rgba(139, 94, 60, 0.22);
}
</style>

<style>
/*
 * 右键菜单刻意不加 scoped：它被 Teleport 到 body，
 * scoped 会给选择器加上本组件的属性，而移到 body 之后那层属性不在了，样式会失效。
 */
.rb-menu {
  position: fixed;
  z-index: 3000;
  min-width: 168px;
  padding: 4px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-m);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
}

.rb-menu-preview {
  padding: 6px 8px 8px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--color-line);
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.6;
  max-width: 220px;
}

.rb-menu-item {
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
.rb-menu-item:hover { background: var(--color-paper-warm); }

/* ==================================================================== */
/* 视觉打磨（2026-10 重做：圆润、柔和、有层次）                          */
/* ==================================================================== */

/* 标题用衬线、字号拉开，并去掉原来的硬分隔线 */
.ws-head { padding: 6px 4px 12px; }

.ws-title {
  font-family: var(--font-serif);
  font-size: 12px;
  letter-spacing: 0.16em;
  color: var(--color-muted);
}

/* 悬浮气泡：圆形 + 柔和阴影，替代原来的方框 */
.ws-bubble {
  border-radius: 50%;
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out),
              transform var(--dur-fast) var(--ease-spring);
}
.ws-bubble:hover {
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
  transform: scale(1.06);
}

/* ---------- 项目 / 会话 ---------- */

.ws-project-row {
  border-radius: var(--radius-s);
  transition: background var(--dur) var(--ease-out),
              box-shadow var(--dur) var(--ease-out);
}
.ws-project-row:hover { background: rgba(255, 255, 255, 0.55); }
.ws-project-head.active { background: var(--color-panel); box-shadow: var(--shadow-xs); }

.ws-project-name {
  font-size: 13.5px;
  letter-spacing: 0.02em;
}

/* 计数徽章：药丸形、淡棕底 */
.ws-count {
  border-radius: var(--radius-pill);
  background: rgba(169, 118, 79, 0.12);
  color: var(--color-brown-deep);
  padding: 1px 7px;
  font-size: 10.5px;
}

/* 会话条目：圆角 + 左侧一条细棕线做"当前"标记 */
.ws-conv {
  border-radius: var(--radius-xs);
  transition: background var(--dur) var(--ease-out);
}
.ws-conv:hover { background: rgba(255, 255, 255, 0.5); }
.ws-conv.active {
  background: var(--color-panel);
  box-shadow: var(--shadow-xs);
}

.ws-conv.active .ws-conv-mark {
  background: linear-gradient(180deg, #c08f66, var(--color-brown));
  border-radius: var(--radius-pill);
}

.ws-conv-title { font-size: 12.5px; }
.ws-conv-time { font-size: 10.5px; opacity: 0.7; }

/* ---------- 浮层 ---------- */

.ws-picker,
.ws-menu {
  border-radius: var(--radius-m);
  border-color: var(--color-line-soft);
  box-shadow: var(--shadow-float);
  padding: 6px;
  animation: mj-pop var(--dur) var(--ease-spring) both;
  transform-origin: top left;
}

.ws-menu.is-floating { transform-origin: top left; }

.ws-picker-title {
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--color-muted);
  padding: 6px 10px 8px;
}

.ws-picker-item {
  border-radius: var(--radius-xs);
  transition: background var(--dur) var(--ease-out);
}
.ws-picker-item:hover { background: var(--color-brown-tint); }

.ws-menu-item {
  border-radius: var(--radius-xs);
  transition: background var(--dur) var(--ease-out),
              color var(--dur) var(--ease-out);
}
.ws-menu-item:hover { background: var(--color-brown-tint); }
.ws-menu-item.is-danger:hover { background: rgba(179, 68, 51, 0.09); }

.ws-empty { line-height: 1.9; }

</style>
