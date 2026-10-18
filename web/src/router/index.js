import { createRouter, createWebHistory } from 'vue-router'
import LandingPage from '../v3/views/LandingPage.vue'
import WorkbenchV3 from '../v3/views/WorkbenchV3.vue'
import BookPageV3 from '../v3/views/BookPageV3.vue'
import AiSettingsPageV3 from '../v3/views/AiSettingsPageV3.vue'
import PreferencesPageV3 from '../v3/views/PreferencesPageV3.vue'
import UiPreviewPage from '../v3/views/UiPreviewPage.vue'

/*
 * 路由表（v3 已全面接管）。
 *
 * - `/` 作品架（首页）
 * - `/workbench` 创作工作台（策划 / 写作两种模式，由 query.mode 决定）
 * - `/book` 成书阅读
 * - `/ai-settings` 模型接入（服务商 / 模型库 / 双 Agent 模型）
 * - `/settings` 偏好设置（主题 / 思维链 / 发送键）
 * - `/ui-preview` 设计系统样式指南（开发用，兼作设计定稿页）
 * - 旧页面（人物档案、剧情蓝图、故事设定库、旧工作台）已被工作台右栏与新版取代，
 *   旧书签统一回首页。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: LandingPage },
    { path: '/workbench', name: 'workbench', component: WorkbenchV3 },
    { path: '/book', name: 'book', component: BookPageV3 },
    { path: '/ai-settings', name: 'ai-settings', component: AiSettingsPageV3 },
    { path: '/settings', name: 'settings', component: PreferencesPageV3 },
    { path: '/ui-preview', name: 'ui-preview', component: UiPreviewPage },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

export default router
