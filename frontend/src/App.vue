<script setup>
import { computed, onMounted, ref } from 'vue'
import router from './router'
import api from './api'

import HouseView from './views/HouseView.vue'
import PigView from './views/PigView.vue'
import BatchView from './views/BatchView.vue'
import TransferView from './views/TransferView.vue'
import ImmuneView from './views/ImmuneView.vue'
import TraceView from './views/TraceView.vue'

// 底部悬浮 Dock：6 个圆形图标按钮，hover / 选中时抬起并显示文字标签
const modules = [
  {
    key: 'house',
    label: '栋舍',
    path: '/house',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><path d="M3 11.2 12 4l9 7.2"/><path d="M5.5 10.5V20h13v-9.5"/><path d="M9.5 20v-5.5h5V20"/><path d="M3 20h18"/></svg>'
  },
  {
    key: 'pig',
    label: '猪只',
    path: '/pig',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><path d="M4 12.5c0-3.3 2.7-5.5 6-5.5h4c3.3 0 6 2.2 6 5.5 0 2.6-1.6 4.5-4 4.5h-1v2.5h-2.5V17H11v2.5H8.5V17H7c-2 0-3-1.9-3-4.5Z"/><path d="M14.5 11.5h.01"/><path d="M6 12c-.9-.6-.9-1.9 0-2.6"/></svg>'
  },
  {
    key: 'batch',
    label: '批次',
    path: '/batch',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><rect x="3.5" y="4.5" width="7" height="6" rx="1.4"/><rect x="13.5" y="4.5" width="7" height="6" rx="1.4"/><rect x="3.5" y="13.5" width="7" height="6" rx="1.4"/><path d="M17 13.5v6"/><path d="M13.5 16.5h7"/></svg>'
  },
  {
    key: 'transfer',
    label: '转栏',
    path: '/transfer',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><path d="M4 8.5h11"/><path d="M11.5 5.5 15 8.5l-3.5 3"/><path d="M20 15.5H9"/><path d="M12.5 12.5 9 15.5l3.5 3"/></svg>'
  },
  {
    key: 'immune',
    label: '防疫',
    path: '/immune',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><path d="M8 3.5h8"/><path d="M10 3.5v4L5.6 16a3.4 3.4 0 0 0 3 5h6.8a3.4 3.4 0 0 0 3-5L14 7.5v-4"/><path d="M7.5 14.5h9"/><path d="M12 17.2h.01"/></svg>'
  },
  {
    key: 'trace',
    label: '溯源',
    path: '/trace',
    svg: '<svg class="dock-svg" viewBox="0 0 24 24"><path d="M12 3.5 19 6v6.2c0 4-2.9 6.6-7 8.3-4.1-1.7-7-4.3-7-8.3V6l7-2.5Z"/><path d="M9 12.2l2.2 2.3L15.5 10"/></svg>'
  }
]

// main.js 里没有 app.use(router)，这里自己驱动路由实例并用 <component :is> 渲染
const viewMap = {
  '/house': HouseView,
  '/pig': PigView,
  '/batch': BatchView,
  '/transfer': TransferView,
  '/immune': ImmuneView,
  '/trace': TraceView
}

const currentPath = computed(() => router.currentRoute.value?.path || '/house')
const currentView = computed(() => viewMap[currentPath.value] || HouseView)

const overview = ref(null)

function go(path) {
  router.push({ path })
}

async function loadOverview() {
  try {
    overview.value = await api.stats.overview()
  } catch (e) {
    overview.value = null
  }
}

onMounted(() => {
  loadOverview()
  // 路由没有 install，初始 currentRoute 是 START_LOCATION，这里按地址栏补一次导航
  const hash = window.location.hash.replace(/^#/, '')
  router.replace(hash.startsWith('/') && hash !== '/' ? hash : '/house')
})
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <div class="brand-mark"><span>猪</span></div>
      <div>
        <div class="brand-title">规模化养猪场 · 栋舍与出栏批次管理</div>
        <div class="brand-sub">配怀 → 分娩 → 保育 → 育肥 → 出栏批次 → 防疫溯源</div>
      </div>
      <div class="top-stats" v-if="overview">
        <div class="top-stat"><b>{{ overview.houseCount }}</b><span>栋舍</span></div>
        <div class="top-stat"><b>{{ overview.penCount }}</b><span>栏位</span></div>
        <div class="top-stat"><b>{{ overview.onFarmCount }}</b><span>在栏猪只</span></div>
        <div class="top-stat"><b>{{ overview.batchCount }}</b><span>出栏批次</span></div>
        <div class="top-stat"><b>{{ overview.vaccineCount }}</b><span>免疫记录</span></div>
        <div class="top-stat"><b>{{ overview.traceCount }}</b><span>溯源记录</span></div>
      </div>
    </header>

    <main class="app-content">
      <component :is="currentView" :key="currentPath" />
    </main>

    <!-- 底部悬浮 Dock 图标导航 -->
    <nav class="dock">
      <div
        v-for="mod in modules"
        :key="mod.key"
        class="dock-item"
        :class="{ active: currentPath === mod.path }"
        @click="go(mod.path)"
      >
        <span class="dock-label">{{ mod.label }}</span>
        <span v-html="mod.svg"></span>
        <i class="dock-dot"></i>
      </div>
    </nav>
  </div>
</template>
