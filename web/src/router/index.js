import { createRouter, createWebHistory } from 'vue-router'
import ProjectListPage from '../views/ProjectListPage.vue'
import CharactersPage from '../views/CharactersPage.vue'
import SettingLibraryPage from '../views/SettingLibraryPage.vue'
import PreferencesPage from '../views/PreferencesPage.vue'
import AiSettingsPage from '../views/AiSettingsPage.vue'
import BookPage from '../views/BookPage.vue'
import OutlinePage from '../views/OutlinePage.vue'
import WorkbenchPage from '../views/WorkbenchPage.vue'

/*
 * 路由表。
 *
 * 几条约定：
 * - /workbench 是新主入口。原来的 /chapters（章节创作）与 /chat（AI 助手）
 *   已被它的写作/策划两个模式取代，故移除。
 * - /settings 是「偏好设置」（主题、思维链、发送键）。
 *   旧的「故事设定库」挪到 /setting-library —— 它已无导航入口
 *   （设定在工作台右栏就能看），仅保留可访问。
 * - 未知路径一律回工作台：旧书签也能落到新入口，而不是白屏。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/workbench', name: 'workbench', component: WorkbenchPage },
    { path: '/', name: 'projects', component: ProjectListPage },
    { path: '/characters', name: 'characters', component: CharactersPage },
    { path: '/settings', name: 'settings', component: PreferencesPage },
    { path: '/setting-library', name: 'setting-library', component: SettingLibraryPage },
    { path: '/outline', name: 'outline', component: OutlinePage },
    { path: '/ai-settings', name: 'ai-settings', component: AiSettingsPage },
    { path: '/book', name: 'book', component: BookPage },
    { path: '/:pathMatch(.*)*', redirect: '/workbench' }
  ]
})

export default router
