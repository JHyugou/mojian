import { createRouter, createWebHistory } from 'vue-router'
import ProjectListPage from '../views/ProjectListPage.vue'
import CharactersPage from '../views/CharactersPage.vue'
import SettingsPage from '../views/SettingsPage.vue'
import AiSettingsPage from '../views/AiSettingsPage.vue'
import BookPage from '../views/BookPage.vue'
import OutlinePage from '../views/OutlinePage.vue'
import WorkbenchPage from '../views/WorkbenchPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 创作工作台是新主入口。原来的 /chapters（章节创作）与 /chat（AI 助手）
    // 已经被它的写作/策划两个模式取代，故移除。
    { path: '/workbench', name: 'workbench', component: WorkbenchPage },
    { path: '/', name: 'projects', component: ProjectListPage },
    { path: '/characters', name: 'characters', component: CharactersPage },
    { path: '/settings', name: 'settings', component: SettingsPage },
    { path: '/outline', name: 'outline', component: OutlinePage },
    { path: '/ai-settings', name: 'ai-settings', component: AiSettingsPage },
    { path: '/book', name: 'book', component: BookPage },
    // 未知路径一律回工作台：旧书签 /chapters、/chat 也能落到新入口，而不是白屏
    { path: '/:pathMatch(.*)*', redirect: '/workbench' }
  ]
})

export default router
