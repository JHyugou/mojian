<script setup>
/**
 * 蓝图树递归节点。typeLabel 展示层级徽标；CHAPTER 节点提供加子节点外的全部操作。
 */
defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 }
})

const emit = defineEmits(['add-child', 'edit', 'delete'])

const typeLabel = { VOLUME: '卷', ARC: '篇', CHAPTER: '章' }

function childTypes(nodeType) {
  if (nodeType === 'VOLUME') return ['ARC', 'CHAPTER']
  if (nodeType === 'ARC') return ['CHAPTER']
  return []
}
</script>

<template>
  <div class="tree-node" :style="{ marginLeft: depth * 20 + 'px' }">
    <div class="node-row">
      <span class="node-type" :class="'type-' + node.nodeType">{{ typeLabel[node.nodeType] || node.nodeType }}</span>
      <span class="node-name" :title="node.name">{{ node.name }}</span>
      <span v-if="node.status && node.status !== 'PLANNED'" class="node-status">{{ node.status === 'DONE' ? '已完成' : '写作中' }}</span>
      <span class="node-ops">
        <button
          v-for="t in childTypes(node.nodeType)"
          :key="t"
          class="btn btn-secondary btn-mini"
          type="button"
          @click="emit('add-child', node, t)"
        >
          +{{ typeLabel[t] }}
        </button>
        <button class="btn btn-secondary btn-mini" type="button" @click="emit('edit', node)">编辑</button>
        <button class="btn btn-secondary btn-mini node-delete" type="button" @click="emit('delete', node)">删</button>
      </span>
    </div>
    <p v-if="node.summary" class="node-desc">{{ node.summary }}</p>

    <OutlineTreeNode
      v-for="child in node.children"
      :key="child.id"
      :node="child"
      :depth="depth + 1"
      @add-child="(n, t) => emit('add-child', n, t)"
      @edit="(n) => emit('edit', n)"
      @delete="(n) => emit('delete', n)"
    />
  </div>
</template>

<style scoped>
.tree-node {
  margin-top: 6px;
}

.node-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--color-panel);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
}

.node-type {
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-family: var(--font-serif);
  color: #fff;
  background: var(--color-muted);
}

.type-VOLUME {
  background: var(--color-dai-deep);
}

.type-ARC {
  background: #7ba08c;
}

.type-CHAPTER {
  background: var(--color-cinnabar);
}

.node-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-status {
  font-size: 11px;
  color: var(--color-dai-deep);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-s);
  padding: 0 8px;
}

.node-ops {
  margin-left: auto;
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.node-row:hover .node-ops,
.node-row:focus-within .node-ops {
  opacity: 1;
}

.node-delete {
  color: var(--color-danger);
}

.node-desc {
  margin: 4px 0 0 30px;
  font-size: 12px;
  color: var(--color-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
