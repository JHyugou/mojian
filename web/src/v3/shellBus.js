import { ref } from 'vue'

/**
 * 外壳与页面之间的小型通信总线。
 *
 * <p>外壳（左轨/工作区树/顶栏/命令面板）现在由 App 常驻挂载，
 * 页面切换时它不再重建 —— 这是"点首页/成书时侧边栏不消失"的关键。
 * 页面不再各自包一层外壳，只需要：
 * <ul>
 *   <li>把顶栏内容 <code>&lt;Teleport to="#shell-topbar"&gt;</code> 进去</li>
 *   <li>通过 {@link useShellCommands} 注册自己的专属命令（如首页的新建/导入）</li>
 * </ul>
 */
const commands = ref([])

export function useShellCommands() {
  return {
    commands,
    set(list) {
      commands.value = Array.isArray(list) ? list : []
    },
    clear() {
      commands.value = []
    }
  }
}
