import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import HouseView from '../views/HouseView.vue'
import PigView from '../views/PigView.vue'
import BatchView from '../views/BatchView.vue'
import TransferView from '../views/TransferView.vue'
import ImmuneView from '../views/ImmuneView.vue'
import TraceView from '../views/TraceView.vue'

const routes = [
  { path: '/', redirect: '/house' },
  { path: '/house', name: 'house', component: HouseView },
  { path: '/pig', name: 'pig', component: PigView },
  { path: '/batch', name: 'batch', component: BatchView },
  { path: '/transfer', name: 'transfer', component: TransferView },
  { path: '/immune', name: 'immune', component: ImmuneView },
  { path: '/trace', name: 'trace', component: TraceView },
  { path: '/:pathMatch(.*)*', redirect: '/house' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
