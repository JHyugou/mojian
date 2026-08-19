import { createRouter, createWebHistory } from 'vue-router'
import ProjectListPage from '../views/ProjectListPage.vue'
import CharactersPage from '../views/CharactersPage.vue'
import SettingsPage from '../views/SettingsPage.vue'
import OutlinePage from '../views/OutlinePage.vue'
import ChaptersPage from '../views/ChaptersPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'projects', component: ProjectListPage },
    { path: '/characters', name: 'characters', component: CharactersPage },
    { path: '/settings', name: 'settings', component: SettingsPage },
    { path: '/outline', name: 'outline', component: OutlinePage },
    { path: '/chapters', name: 'chapters', component: ChaptersPage }
  ]
})

export default router
