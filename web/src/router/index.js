import { createRouter, createWebHistory } from 'vue-router'
import ProjectListPage from '../views/ProjectListPage.vue'
import PlaceholderPage from '../views/PlaceholderPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'projects', component: ProjectListPage },
    { path: '/characters', name: 'characters', component: PlaceholderPage, props: { title: '人物档案' } },
    { path: '/settings', name: 'settings', component: PlaceholderPage, props: { title: '故事设定库' } },
    { path: '/outline', name: 'outline', component: PlaceholderPage, props: { title: '剧情蓝图' } },
    { path: '/chapters', name: 'chapters', component: PlaceholderPage, props: { title: '章节创作' } }
  ]
})

export default router
