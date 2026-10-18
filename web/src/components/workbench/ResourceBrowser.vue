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

<style scoped>
/* ==================================================================== */
/* 资料浏览器：素笺流韵 v2                                              */
/* ==================================================================== */

.rb-tip {
  padding: 2px 2px 12px;
  font-size: 11.5px;
  line-height: 1.85;
  color: var(--color-muted);
}

.rb-list { display: flex; flex-direction: column; gap: 2px; }

.rb-item { animation: mj-fade var(--dur) var(--ease-out) both; }

/* 条目头：药丸。hover 染雾，不做位移 —— 这一栏是密集列表，动了会晃眼 */
.rb-item-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 8px 11px;
  border: 0;
  border-radius: var(--radius-s);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background var(--dur) var(--ease-out);
}
.rb-item-head:hover { background: var(--tint-white); }

/* 展开箭头 */
.rb-caret {
  flex: none;
  width: 12px;
  font-size: 9px;
  color: var(--color-muted);
  transition: color var(--dur) var(--ease-out);
}
.rb-item-head:hover .rb-caret { color: var(--color-brown); }

.rb-title {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--color-ink-soft);
  word-break: break-word;
}

/* 分组标题：衬线 + 加粗，和普通条目拉开层次 */
.rb-title.is-group {
  font-family: var(--font-serif);
  font-weight: 600;
  font-size: 13px;
  color: var(--color-brown-deep);
}

/* 条目内容：缩进 + 左侧一条淡线，像"树的一支" */
.rb-body {
  margin: 2px 0 4px 18px;
  padding: 10px 14px;
  border-left: 2px solid var(--color-line-soft);
  border-radius: 0 var(--radius-s) var(--radius-s) 0;
  background: var(--color-paper-warm);
  animation: mj-slide-in var(--dur) var(--ease-out) both;
}

.rb-text {
  font-size: 12px;
  line-height: 1.9;
  color: var(--color-ink-soft);
  word-break: break-word;
}

/* 选中文字：淡棕底。浏览资料时经常要选一段，这个颜色得舒服 */
.rb-text ::selection {
  background: var(--tint-strong);
  color: var(--color-ink);
}

.rb-empty {
  padding: 24px 14px;
  text-align: center;
  font-size: 12px;
  line-height: 1.95;
  color: var(--color-muted);
}

.rb-empty-block {
  padding: 16px 14px;
  border-radius: var(--radius-m);
  background: var(--color-panel);
  text-align: center;
  font-size: 12px;
  line-height: 1.9;
  color: var(--color-muted);
}

/* ---------- 右键菜单 ---------- */

.rb-menu {
  position: fixed;
  z-index: 200;
  padding: 6px;
  border-radius: var(--radius-s);
  background: var(--color-panel);
  box-shadow: var(--shadow-float);
  animation: mj-pop var(--dur-fast) var(--ease-spring) both;
}

.rb-menu-preview {
  padding: 6px 11px 8px;
  font-size: 11px;
  color: var(--color-muted);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rb-menu-item {
  display: block;
  width: 100%;
  padding: 8px 12px;
  border: 0;
  border-radius: var(--radius-xs);
  background: none;
  cursor: pointer;
  text-align: left;
  font-size: 12.5px;
  color: var(--color-ink);
  transition: background var(--dur) var(--ease-out);
}
.rb-menu-item:hover { background: var(--tint); }
</style>
