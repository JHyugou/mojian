<script setup>
/**
 * 图标组件。
 *
 * <p>为什么不用文字符号（＋ ··· 📁）：
 * 它们是<b>字体</b>，字号、基线、粗细都随字体变化，和界面线条对不齐；
 * 而 SVG 是矢量，粗细可控、和文字的基线对齐可以精确调。
 * 一排图标里混进一个字体符号，整行就歪了。
 *
 * 全部用描边（stroke）而不是填充：和国风的细线感一致。
 */
defineProps({
  name: { type: String, required: true },
  size: { type: Number, default: 16 },
  /** 描边粗细。图标小的时候要略粗一点才看得清 */
  width: { type: Number, default: 1.6 }
})

const PATHS = {
  // 更多操作
  dots: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
  // 新建
  plus: '<path d="M12 5v14M5 12h14"/>',
  // 工作区（文件夹）
  folder: '<path d="M3 7.5A1.5 1.5 0 0 1 4.5 6h4L10.5 8h9A1.5 1.5 0 0 1 21 9.5v7A1.5 1.5 0 0 1 19.5 18h-15A1.5 1.5 0 0 1 3 16.5z"/>',
  // 修改内容
  edit: '<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z"/><path d="M14.5 5.5l4 4"/>',
  // 删除
  trash: '<path d="M4 7h16"/><path d="M9.5 7V5h5v2"/><path d="M6.5 7l1 13h9l1-13"/>',
  // 无工作区的会话（起草新书 = 从空白起笔）
  sparkle: '<path d="M12 3l1.9 5.3L19 10l-5.1 1.7L12 17l-1.9-5.3L5 10l5.1-1.7z"/><path d="M18.5 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
  // 展开/折叠
  caret: '<path d="M8 10l4 4 4-4"/>',
  // 搜索
  search: '<circle cx="11" cy="11" r="6"/><path d="M15.5 15.5L20 20"/>',
  // 采纳模式（盾牌：权限的通用隐喻）
  shield: '<path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6z"/>',
  // 终止
  stop: '<rect x="7" y="7" width="10" height="10" rx="1.5"/>',
  // 全部采纳（双勾）
  checkAll: '<path d="M2 13l4 4L14 9"/><path d="M11 15l1.5 1.5L21 8"/>',
  // 单选已选中
  check: '<path d="M4.5 12.5l5 5 10-11"/>',
  // 回车键
  enter: '<path d="M9 10l-5 5 5 5"/><path d="M4 15h11a4 4 0 0 0 4-4V5"/>',
  // 回滚到这一步：一道干净的回转箭头（箭头 + 平滑回弯）
  undo: '<path d="M4 9h9.5a4.5 4.5 0 0 1 0 9H10"/><path d="M7.5 5.5L4 9l3.5 3.5"/>',
  // ===== v3 新图标 =====
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/>',
  moon: '<path d="M20 13.2A8 8 0 1 1 10.8 4a6.4 6.4 0 0 0 9.2 9.2z"/>',
  book: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M4 20.5A2.5 2.5 0 0 1 6.5 18H20"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v2M12 19.5v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M2.5 12h2M19.5 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4"/>',
  arrowRight: '<path d="M4 12h15"/><path d="M13 6l6 6-6 6"/>',
  upload: '<path d="M12 16V4"/><path d="M7 9l5-5 5 5"/><path d="M4 16v3a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 19v-3"/>',
  download: '<path d="M12 4v12"/><path d="M7 11l5 5 5-5"/><path d="M4 16v3a1.5 1.5 0 0 0 1.5 1.5h13A1.5 1.5 0 0 0 20 19v-3"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  command: '<path d="M9 6a3 3 0 1 0-3 3h3zM15 6a3 3 0 1 1 3 3h-3zM9 18a3 3 0 1 1-3-3h3zM15 18a3 3 0 1 0 3-3h-3z"/><rect x="9" y="9" width="6" height="6" rx="1"/>',
  quill: '<path d="M4 20c6-2 10-6 12-12l2-2 2 2-2 2C12 16 8 18 4 20z"/><path d="M4 20c2-4 4-6 7-8"/>',
  layers: '<path d="M12 3l9 5-9 5-9-5z"/><path d="M3 13l9 5 9-5"/><path d="M3 17l9 5 9-5"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  quote: '<path d="M9 7H5v5h4c0 3-2 4-4 5"/><path d="M19 7h-4v5h4c0 3-2 4-4 5"/>',
  home: '<path d="M4 11l8-6.5 8 6.5"/><path d="M6 10v9h12v-9"/><path d="M10 19v-5h4v5"/>',
  help: '<circle cx="12" cy="12" r="8.8"/><path d="M9.4 9.2a2.7 2.7 0 1 1 3.8 2.5c-.8.4-1.2 1-1.2 1.8v.4"/><circle cx="12" cy="17.2" r=".8" fill="currentColor" stroke="none"/>',
  cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/><path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3"/>'
}
</script>

<template>
  <svg
    class="ic"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="width"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="PATHS[name] || ''"
  ></svg>
</template>

<style scoped>
.ic {
  display: block;
  flex-shrink: 0;
}
</style>
