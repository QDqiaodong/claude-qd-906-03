<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const HOUSE_TYPES = [
  { value: 'GESTATION', label: '配怀' },
  { value: 'FARROWING', label: '分娩' },
  { value: 'NURSERY', label: '保育' },
  { value: 'FINISHING', label: '育肥' },
  { value: 'ISOLATION', label: '隔离' }
]

const houses = ref([])
const typeFilter = ref('')
const activePenId = ref(null)
const drawer = ref(false)
const penDetail = ref(null)
const penPigs = ref([])

const houseDialog = ref(false)
const penDialog = ref(false)
const houseForm = ref(emptyHouse())
const penForm = ref(emptyPen())
const editPen = ref(false)

// 状态切换确认弹窗
const statusDialog = ref(false)
const statusSaving = ref(false)
const review = ref(null)
const operator = ref('')
const reason = ref('')
const requestNo = ref('')
// 处置留痕
const logDrawer = ref(false)
const statusLogs = ref([])

function emptyHouse () {
  return { houseCode: '', houseName: '', houseType: 'FINISHING', areaSqm: null, keeper: '', note: '' }
}
function emptyPen () {
  return { houseId: null, penCode: '', penName: '', capacity: 10, status: 'NORMAL', note: '' }
}

const filtered = computed(() => {
  if (!typeFilter.value) return houses.value
  return houses.value.filter((h) => h.houseType === typeFilter.value)
})

const summary = computed(() => {
  let penCount = 0
  let pigCount = 0
  let capacity = 0
  for (const h of houses.value) {
    penCount += h.pens.length
    pigCount += h.actualPigCount || 0
    capacity += h.capacity || 0
  }
  return { penCount, pigCount, capacity, usage: capacity ? Math.round((pigCount * 100) / capacity) : 0 }
})

// 全场待处理异常（恢复在用也不会清除）
const globalAnomalies = computed(() => {
  const rows = []
  for (const h of houses.value) {
    for (const a of h.anomalies || []) {
      rows.push({ ...a, houseCode: h.houseCode, houseId: h.id, houseStatus: h.status })
    }
  }
  return rows
})

async function load () {
  houses.value = await api.house.grid()
}

function typeLabel (type) {
  const hit = HOUSE_TYPES.find((t) => t.value === type)
  return hit ? hit.label : type
}

function makeRequestNo () {
  if (window.crypto?.randomUUID) return `HS-${crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase()}`
  return `HS-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`.toUpperCase()
}

async function openPen (pen) {
  activePenId.value = pen.id
  penDetail.value = pen
  penPigs.value = await api.house.penPigs(pen.id)
  drawer.value = true
}

// ---------------- 状态切换：先预检，操作员看过影响清单与阻塞原因后确认 ----------------

async function openStatus (house, targetStatus) {
  try {
    review.value = await api.house.statusReview(house.id, targetStatus)
    reason.value = ''
    requestNo.value = makeRequestNo()
    statusDialog.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function confirmStatus () {
  if (!operator.value.trim()) {
    ElMessage.warning('请填写确认操作的操作员')
    return
  }
  if (!review.value?.canApply) {
    ElMessage.warning('当前仍有阻塞项，不能执行该切换')
    return
  }
  statusSaving.value = true
  try {
    const result = await api.house.status(review.value.houseId, {
      status: review.value.targetStatus,
      operator: operator.value.trim(),
      reason: reason.value.trim(),
      requestNo: requestNo.value
    })
    ElMessage.success(`栋舍已切换为「${result.targetStatusName}」`)
    statusDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    statusSaving.value = false
  }
}

async function openLogs (house) {
  statusLogs.value = await api.house.statusLogs(house?.id)
  logDrawer.value = true
}

function modeTagType (target) {
  return { MAINTAIN: 'primary', PENDING_DISABLE: 'warning', DISABLED: 'info', ACTIVE: 'success' }[target] || ''
}

function statusTagClass (status) {
  return { ACTIVE: 'st-active', MAINTAIN: 'st-maintain', PENDING_DISABLE: 'st-pending', DISABLED: 'st-disabled' }[status] || ''
}

function penBlocked (house, pen) {
  return house.restricted || pen.status === 'REPAIR' || pen.status === 'CLEANING'
}

async function submitHouse () {
  try {
    await api.house.create(houseForm.value)
    ElMessage.success('栋舍已创建')
    houseDialog.value = false
    houseForm.value = emptyHouse()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openPenDialog (house) {
  editPen.value = false
  penForm.value = emptyPen()
  penForm.value.houseId = house.id
  penForm.value.capacity = defaultCapacity(house.houseType)
  penDialog.value = true
}

function defaultCapacity (type) {
  return { GESTATION: 6, FARROWING: 1, NURSERY: 20, FINISHING: 30, ISOLATION: 8 }[type] || 10
}

async function submitPen () {
  try {
    if (editPen.value) {
      await api.house.updatePen(penForm.value.id, penForm.value)
      ElMessage.success('栏位已更新')
    } else {
      await api.house.createPen(penForm.value)
      ElMessage.success('栏位已创建')
    }
    penDialog.value = false
    drawer.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function editThisPen () {
  editPen.value = true
  penForm.value = { ...penDetail.value, capacity: penDetail.value.capacity }
  penDialog.value = true
}

async function removeThisPen () {
  await ElMessageBox.confirm('确认删除该栏位（栏内不能有猪只）？', '删除栏位', { type: 'warning' })
  await api.house.removePen(penDetail.value.id)
  ElMessage.success('栏位已删除')
  drawer.value = false
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">栋舍与栏位</span>
      <span class="view-tip">平面格子图：一格一栏位，点格子看存栏；检修立即生效原地照护，停用先腾空再生效</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: typeFilter === '' }" @click="typeFilter = ''">全部栋舍</span>
      <span v-for="t in HOUSE_TYPES" :key="t.value" class="chip" :class="{ on: typeFilter === t.value }" @click="typeFilter = t.value">{{ t.label }}</span>
      <el-button size="small" style="margin-left: 8px" @click="openLogs(null)">处置留痕</el-button>
      <el-button size="small" type="primary" style="margin-left: auto" @click="houseDialog = true">新建栋舍</el-button>
    </div>

    <div class="grid-3" style="margin-bottom: 12px">
      <div class="mini-card"><div class="k">栋舍 / 栏位</div><div class="v">{{ houses.length }} / {{ summary.penCount }}</div></div>
      <div class="mini-card"><div class="k">在栏猪只 / 总容量（实测）</div><div class="v">{{ summary.pigCount }} / {{ summary.capacity }}</div></div>
      <div class="mini-card"><div class="k">全场存栏率</div><div class="v">{{ summary.usage }}%</div></div>
    </div>

    <!-- 全局遗留异常：恢复在用也不会清除，必须逐项处置 -->
    <el-alert
      v-if="globalAnomalies.length"
      class="anomaly-banner"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>
        存栏待处理异常 {{ globalAnomalies.length }} 条（超容 / 位置不明 / 计数不符 / 栏位未完操作），恢复在用不会清除，请逐项处置
      </template>
      <div class="anomaly-line" v-for="(a, i) in globalAnomalies" :key="i">
        <b>{{ a.houseCode }}</b> · {{ a.codeName }} · {{ a.message }}
      </div>
    </el-alert>

    <div class="grid-2" style="grid-template-columns: repeat(auto-fill, minmax(440px, 1fr))">
      <div
        v-for="house in filtered"
        :key="house.id"
        class="house-card"
        :class="{ disabled: house.disabled, maintain: house.maintain, pending: house.pendingDisable }"
      >
        <div class="house-head">
          <span class="house-code">{{ house.houseCode }}</span>
          <span class="tag solid" :style="{ background: house.houseTypeColor }">{{ house.houseTypeName }}</span>
          <span class="tag solid house-status-tag" :class="statusTagClass(house.status)">{{ house.statusName }}</span>
          <el-button size="small" link @click="openLogs(house)">留痕</el-button>
          <el-button size="small" link @click="openPenDialog(house)" :disabled="house.disabled">+ 栏位</el-button>
        </div>
        <div class="house-meta">
          {{ house.houseName }} · 饲养员 {{ house.keeper || '—' }} · {{ house.areaSqm || 0 }}㎡ ·
          {{ house.penCount }} 栏 · 实测存栏 {{ house.actualPigCount }}/{{ house.capacity }}
          <span v-if="house.pigCount !== house.actualPigCount" class="count-warn">
            （栏位计数合计 {{ house.pigCount }}，与台账不符）
          </span>
        </div>

        <!-- 受限原因 -->
        <div v-if="house.restrictReason" class="restrict-banner">
          <span class="rb-icon">⛔</span>{{ house.restrictReason }}
        </div>

        <!-- 待清空 / 检修存栏处置进度 -->
        <div v-if="house.pendingDisable || house.maintain" class="dispose-progress">
          <div class="dp-head">
            <span>{{ house.pendingDisable ? '腾空进度（只准迁出）' : '原地照护存栏' }}</span>
            <span>{{ house.clearedPenCount }}/{{ house.pens.length }} 栏已清空 · 待迁出 {{ house.pendingMoveCount }} 头</span>
          </div>
          <div class="load-bar" :class="{ full: false }">
            <i :style="{ width: (house.pens.length ? Math.round((house.clearedPenCount * 100) / house.pens.length) : 0) + '%' }"></i>
          </div>
        </div>

        <!-- 本栋待处理异常 -->
        <div v-for="(a, i) in house.anomalies" :key="i" class="anomaly-chip">
          <span class="tag solid anomaly-tag">{{ a.codeName }}</span>{{ a.message }}
        </div>

        <!-- 状态操作：每一种流转都先弹预检、再由操作员确认 -->
        <div class="status-actions">
          <template v-if="house.status === 'ACTIVE'">
            <el-button size="small" type="primary" plain @click="openStatus(house, 'MAINTAIN')">检修（立即生效·原地照护）</el-button>
            <el-button size="small" type="warning" plain @click="openStatus(house, 'PENDING_DISABLE')">申请停用（先腾空）</el-button>
          </template>
          <template v-else-if="house.status === 'MAINTAIN'">
            <el-button size="small" type="warning" plain @click="openStatus(house, 'PENDING_DISABLE')">转申请停用</el-button>
            <el-button size="small" type="success" @click="openStatus(house, 'ACTIVE')">恢复在用</el-button>
          </template>
          <template v-else-if="house.status === 'PENDING_DISABLE'">
            <el-button size="small" type="danger" @click="openStatus(house, 'DISABLED')">完成停用（需已腾空）</el-button>
            <el-button size="small" type="primary" plain @click="openStatus(house, 'MAINTAIN')">转检修</el-button>
            <el-button size="small" type="success" @click="openStatus(house, 'ACTIVE')">恢复在用</el-button>
          </template>
          <template v-else>
            <el-button size="small" type="success" @click="openStatus(house, 'ACTIVE')">恢复在用</el-button>
          </template>
        </div>

        <div class="pen-grid">
          <div
            v-for="pen in house.pens"
            :key="pen.id"
            class="pen-cell"
            :class="{
              on: activePenId === pen.id,
              blocked: penBlocked(house, pen),
              'pen-anomaly': pen.overCapacity || pen.countMismatch
            }"
            @click="openPen(pen)"
          >
            <div class="pc-name">{{ pen.penName || pen.penCode }}</div>
            <div class="pc-count">{{ pen.actualPigCount }} <span class="pc-cap">/ {{ pen.capacity }} 头</span></div>
            <div class="load-bar" :class="{ full: pen.usageRate >= 1 }"><i :style="{ width: Math.round(pen.usageRate * 100) + '%' }"></i></div>
            <div class="pc-cap">
              {{ pen.statusName }} · 余 {{ pen.remaining }}
              <span v-if="pen.overCapacity" class="pen-flag">超容</span>
              <span v-else-if="pen.countMismatch" class="pen-flag">计数不符</span>
            </div>
          </div>
          <div v-if="!house.pens.length" class="empty-tip">该栋舍还没有栏位</div>
        </div>
      </div>
    </div>

    <!-- 点格子看存栏 -->
    <el-drawer v-model="drawer" :title="penDetail ? penDetail.penCode + ' 存栏明细' : ''" direction="rtl" size="430px">
      <div class="batch-drawer" v-if="penDetail">
        <div class="grid-2" style="grid-template-columns: 1fr 1fr; margin-bottom: 12px">
          <div class="mini-card"><div class="k">实测存栏 / 容量</div><div class="v">{{ penDetail.actualPigCount }} / {{ penDetail.capacity }}</div></div>
          <div class="mini-card"><div class="k">剩余位置</div><div class="v">{{ penDetail.remaining }}</div></div>
        </div>
        <el-alert
          v-if="penDetail.overCapacity || penDetail.countMismatch"
          :title="penDetail.overCapacity ? '该栏已超容' : '栏位计数与台账实测不符，须盘点校正'"
          type="error"
          :closable="false"
          style="margin-bottom: 10px"
        />
        <div class="section-title">栏内猪只（{{ penPigs.length }}）</div>
        <div v-for="pig in penPigs" :key="pig.id" class="pig-row">
          <div>
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">{{ pig.breedName }} · {{ pig.genderName }} · {{ pig.ageInDays }} 日龄 · {{ pig.weightKg }}kg</div>
          </div>
          <span class="tag" style="margin-left: auto">{{ pig.statusName }}</span>
        </div>
        <div v-if="!penPigs.length" class="empty-tip">该栏位暂无猪只</div>
        <div style="margin-top: 12px; display: flex; gap: 8px">
          <el-button size="small" @click="editThisPen">编辑栏位</el-button>
          <el-button size="small" type="danger" plain @click="removeThisPen">删除栏位</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 状态切换确认：预检结果（受影响栏位 / 猪只清单 / 阻塞原因） -->
    <el-drawer
      v-model="statusDialog"
      :title="review ? `${review.houseCode} → ${review.targetStatusName}` : ''"
      direction="rtl"
      size="560px"
    >
      <div class="batch-drawer" v-if="review">
        <el-tag :type="modeTagType(review.targetStatus)" size="large" effect="dark">
          {{ review.dispositionModeName }}
        </el-tag>
        <p class="review-rule">{{ review.ruleText }}</p>

        <el-alert
          v-if="review.blockers.length"
          type="error"
          :closable="false"
          show-icon
          title="阻塞原因（以下项处置前不能生效）"
          style="margin-bottom: 10px"
        >
          <div v-for="(b, i) in review.blockers" :key="i" class="review-line">· {{ b }}</div>
        </el-alert>
        <el-alert
          v-if="review.warnings.length"
          type="warning"
          :closable="false"
          show-icon
          title="操作员须知悉"
          style="margin-bottom: 10px"
        >
          <div v-for="(w, i) in review.warnings" :key="i" class="review-line">· {{ w }}</div>
        </el-alert>
        <el-alert
          v-if="review.canApply && !review.blockers.length"
          type="success"
          :closable="false"
          show-icon
          title="已满足生效条件，可由操作员确认执行"
          style="margin-bottom: 10px"
        />

        <div class="grid-3" style="margin-bottom: 12px">
          <div class="mini-card"><div class="k">受影响栏位</div><div class="v">{{ review.affectedPenCount }}</div></div>
          <div class="mini-card"><div class="k">受影响猪只</div><div class="v">{{ review.affectedPigCount }}</div></div>
          <div class="mini-card"><div class="k">待处理异常</div><div class="v">{{ review.anomalyCount }}</div></div>
        </div>
        <div v-if="review.targetStatus === 'PENDING_DISABLE' || review.targetStatus === 'DISABLED'" class="mini-card" style="margin-bottom: 12px">
          <div class="k">腾空进度</div>
          <div class="v">{{ review.clearedPenCount }}/{{ review.totalPenCount }} 栏已清空 · 未完操作栏位 {{ review.busyPenCount }} 个</div>
        </div>

        <div class="section-title">受影响栏位（{{ review.affectedPens.length }}）</div>
        <div class="review-pens">
          <el-tag
            v-for="pen in review.affectedPens"
            :key="pen.id"
            size="small"
            class="review-pen-tag"
            :type="pen.busy ? 'warning' : pen.overCapacity || pen.countMismatch ? 'danger' : 'info'"
          >
            {{ pen.penCode }} · 实测 {{ pen.actualPigCount }}/{{ pen.capacity }}<template v-if="pen.busy"> · {{ pen.statusName }}</template>
          </el-tag>
        </div>

        <div class="section-title" style="margin-top: 12px">受影响 / 待安置猪只（{{ review.affectedPigs.length }}）</div>
        <div class="review-pigs">
          <div v-for="pig in review.affectedPigs" :key="pig.id" class="pig-row">
            <div>
              <div class="pig-ear">{{ pig.earTag }}</div>
              <div class="pig-sub">{{ pig.breedName }} · {{ pig.statusName }} · {{ pig.weightKg }}kg</div>
            </div>
            <span class="tag" style="margin-left: auto">{{ pig.penCode }}</span>
          </div>
          <div v-if="!review.affectedPigs.length" class="empty-tip">无在栋猪只</div>
        </div>

        <div v-if="review.anomalies.length" class="section-title" style="margin-top: 12px">待处理异常（恢复在用仍保留）</div>
        <div v-for="(a, i) in review.anomalies" :key="i" class="anomaly-chip">
          <span class="tag solid anomaly-tag">{{ a.codeName }}</span>{{ a.message }}
        </div>

        <el-divider />
        <el-form label-width="90px">
          <el-form-item label="操作员" required>
            <el-input v-model="operator" placeholder="确认操作的责任人，如 王建国" />
          </el-form-item>
          <el-form-item label="处置备注">
            <el-input v-model="reason" type="textarea" :rows="2" placeholder="如：加热线检修三天，猪只原地照护" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="statusDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="statusSaving"
          :disabled="!review?.canApply"
          @click="confirmStatus"
        >
          {{ review?.canApply ? `确认切换为「${review.targetStatusName}」` : '仍有阻塞项，不能生效' }}
        </el-button>
      </template>
    </el-drawer>

    <!-- 处置留痕 -->
    <el-drawer v-model="logDrawer" title="栋舍状态处置留痕" direction="rtl" size="520px">
      <el-timeline style="margin-top: 12px">
        <el-timeline-item v-for="log in statusLogs" :key="log.id" :timestamp="log.createdAt + ' · ' + (log.operator || '')">
          <div class="log-line">
            <b>{{ log.houseCode }}</b>
            <span class="tag">{{ log.fromStatusName }}</span> →
            <span class="tag solid" style="background: #ff8f00">{{ log.toStatusName }}</span>
            <span class="pc-cap">生效时存栏 {{ log.pigCount ?? 0 }} 头</span>
          </div>
          <div class="pig-sub">{{ log.reason }}</div>
        </el-timeline-item>
      </el-timeline>
      <div v-if="!statusLogs.length" class="empty-tip">暂无状态切换记录</div>
    </el-drawer>

    <!-- 新建栋舍 -->
    <el-dialog v-model="houseDialog" title="新建栋舍" width="420px">
      <el-form :model="houseForm" label-width="90px">
        <el-form-item label="栋舍编号"><el-input v-model="houseForm.houseCode" placeholder="如 HS-F01" /></el-form-item>
        <el-form-item label="栋舍名称"><el-input v-model="houseForm.houseName" placeholder="如 三号育肥舍" /></el-form-item>
        <el-form-item label="栋舍类型">
          <el-select v-model="houseForm.houseType" style="width: 100%">
            <el-option v-for="t in HOUSE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="建筑面积"><el-input-number v-model="houseForm.areaSqm" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="饲养员"><el-input v-model="houseForm.keeper" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="houseForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="houseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitHouse">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建 / 编辑栏位 -->
    <el-dialog v-model="penDialog" :title="editPen ? '编辑栏位' : '新建栏位'" width="420px">
      <el-form :model="penForm" label-width="90px">
        <el-form-item label="所属栋舍">
          <el-select v-model="penForm.houseId" style="width: 100%">
            <el-option v-for="h in houses" :key="h.id" :label="h.houseCode + ' ' + h.houseName" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="栏位编号"><el-input v-model="penForm.penCode" placeholder="如 HS-F01-03" /></el-form-item>
        <el-form-item label="栏位名称"><el-input v-model="penForm.penName" /></el-form-item>
        <el-form-item label="容量"><el-input-number v-model="penForm.capacity" :min="1" :max="200" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="penForm.status" style="width: 100%">
            <el-option label="正常" value="NORMAL" />
            <el-option label="满栏" value="FULL" />
            <el-option label="清洗消毒" value="CLEANING" />
            <el-option label="维修" value="REPAIR" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="penDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPen">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
