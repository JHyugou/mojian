import { createRouter, createWebHistory } from 'vue-router'
import CharactersPage from '../views/CharactersPage.vue'
import SettingLibraryPage from '../views/SettingLibraryPage.vue'
import PreferencesPage from '../views/PreferencesPage.vue'
import AiSettingsPage from '../views/AiSettingsPage.vue'
import BookPage from '../views/BookPage.vue'
import OutlinePage from '../views/OutlinePage.vue'
import WorkbenchV3 from '../v3/views/WorkbenchV3.vue'
import LandingPage from '../v3/views/LandingPage.vue'
import UiPreviewPage from '../v3/views/UiPreviewPage.vue'

/*
 * 路由表。
 *
 * 几条约定：
 * - `/` 是 v3 全新首页（作品架）；旧的项目列表页被它取代。
 * - `/ui-preview` 是设计定稿页：设计令牌 / 基础组件 / 动效样例都在这，
 *   同时充当"活的样式指南"，后续新页面都从这里取样式。
 * - `/workbench` 是创作工作台，写作/策划两个模式在里面切。
 * - `/settings` 是「偏好设置」（主题、思维链、发送键）。
 *   旧的「故事设定库」挪到 `/setting-library`，仅保留可访问。
 * - 未知路径一律回工作台：旧书签也能落到新入口，而不是白屏。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: LandingPage },
    { path: '/ui-preview', name: 'ui-preview', component: UiPreviewPage },
    { path: '/workbench', name: 'workbench', component: WorkbenchV3 },
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
