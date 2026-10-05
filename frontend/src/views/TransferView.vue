<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const REASONS = [
  { value: 'GROWTH', label: '日龄转栏' },
  { value: 'WEAN', label: '断奶转栏' },
  { value: 'ISOLATE', label: '隔离观察' },
  { value: 'DISEASE', label: '病弱调栏' },
  { value: 'DENSITY', label: '密度调整' },
  { value: 'SELL', label: '出栏前集中' }
]

const houses = ref([])
const pens = ref([])
const transfers = ref([])
const feedings = ref([])
const feedBatches = ref([])

const sourceHouseId = ref(null)
const sourcePenId = ref(null)
const targetPenId = ref(null)
const sourcePigs = ref([])
const selected = ref([])
const reason = ref('GROWTH')
const operator = ref('值班饲养员')

const feedingDialog = ref(false)
const feedForm = ref({ penId: null, feedBatchId: null, feedName: '', feedType: 'FINISHER', amountKg: 50, pigCount: null, operator: '值班饲养员', remark: '' })

const sourcePens = computed(() => pens.value.filter((p) => p.houseId === sourceHouseId.value))
const targetPens = computed(() => pens.value)
const sourcePen = computed(() => pens.value.find((p) => p.id === sourcePenId.value) || null)
const targetPen = computed(() => pens.value.find((p) => p.id === targetPenId.value) || null)
const remainOf = (pen) => Math.max(0, (pen?.capacity || 0) - (pen?.pigCount || 0))

// 栏位 -> 所属栋舍状态：检修 / 待清空 / 停用 / 维修 / 清洗中的栏位不允许作为转入目标（服务端同样强制拦截）
const houseStatusMap = computed(() => {
  const map = {}
  for (const h of houses.value) map[h.id] = h
  return map
})
function penRestricted (pen) {
  const h = houseStatusMap.value[pen?.houseId]
  if (!h) return ''
  if (h.status === 'MAINTAIN') return '栋舍检修中·冻结新增'
  if (h.status === 'PENDING_DISABLE') return '栋舍待清空·只准迁出'
  if (h.status === 'DISABLED') return '栋舍已停用'
  if (pen?.status === 'REPAIR') return '栏位维修中'
  if (pen?.status === 'CLEANING') return '栏位清洗消毒中'
  return ''
}
function houseStatusNameOf (status) {
  return { ACTIVE: '在用', MAINTAIN: '检修', PENDING_DISABLE: '待清空', DISABLED: '停用' }[status] || status
}

async function load () {
  houses.value = await api.transfer.houses()
  pens.value = await api.house.pens()
  transfers.value = await api.transfer.list()
  feedings.value = await api.transfer.feedings()
  feedBatches.value = await api.transfer.feedBatches()
  if (!sourceHouseId.value && houses.value.length) {
    sourceHouseId.value = houses.value[0].id
  }
}

async function pickSourcePen (pen) {
  sourcePenId.value = pen.id
  sourcePigs.value = await api.pig.list({ penId: pen.id })
  selected.value = []
}

function pickTargetPen (pen) {
  targetPenId.value = pen.id
}

function togglePig (pig) {
  const idx = selected.value.indexOf(pig.id)
  if (idx >= 0) selected.value.splice(idx, 1)
  else selected.value.push(pig.id)
}

async function move () {
  if (!sourcePenId.value) return ElMessage.warning('请先选择源栏位')
  if (!targetPenId.value) return ElMessage.warning('请先选择目标栏位')
  if (!selected.value.length) return ElMessage.warning('请先勾选要搬移的猪只')
  const blockedReason = penRestricted(targetPen.value)
  if (blockedReason) return ElMessage.error(`目标栏位不可转入：${blockedReason}`)
  try {
    const list = await api.transfer.create({
      fromPenId: sourcePenId.value,
      toPenId: targetPenId.value,
      pigIds: selected.value,
      reason: reason.value,
      operator: operator.value
    })
    ElMessage.success(`已搬移 ${list.length} 头：${sourcePen.value?.penCode} → ${targetPen.value?.penCode}`)
    selected.value = []
    await load()
    if (sourcePenId.value) sourcePigs.value = await api.pig.list({ penId: sourcePenId.value })
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openFeeding (pen) {
  feedForm.value = {
    penId: pen ? pen.id : null,
    feedBatchId: null,
    feedName: '',
    feedType: 'FINISHER',
    amountKg: 50,
    pigCount: pen ? pen.pigCount : null,
    operator: '值班饲养员',
    remark: ''
  }
  feedingDialog.value = true
}

async function submitFeeding () {
  try {
    await api.transfer.createFeeding(feedForm.value)
    ElMessage.success('投料已登记')
    feedingDialog.value = false
    feedings.value = await api.transfer.feedings()
    pens.value = await api.house.pens()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">转栏与饲喂</span>
      <span class="view-tip">左选源栏位 → 勾选猪只 → 右选目标栏位 → 中间按钮搬移</span>
    </div>

    <div class="chip-bar">
      <span class="chip on">搬迁 {{ selected.length }} 头</span>
      <span>转栏原因</span>
      <el-select v-model="reason" size="small" style="width: 130px">
        <el-option v-for="r in REASONS" :key="r.value" :label="r.label" :value="r.value" />
      </el-select>
      <el-input v-model="operator" size="small" placeholder="操作人" style="width: 140px" />
      <el-button size="small" type="primary" style="margin-left: auto" @click="openFeeding(targetPen || sourcePen)">登记投料</el-button>
    </div>

    <div class="move-board">
      <!-- 左：源栏位 -->
      <div class="side-pane" :class="{ 'on-left': sourcePenId }">
        <div class="section-title">源栏位（{{ sourcePen ? sourcePen.penCode : '未选择' }}）</div>
        <el-select v-model="sourceHouseId" size="small" style="width: 100%; margin-bottom: 10px" placeholder="选择栋舍">
          <el-option v-for="h in houses" :key="h.id" :label="h.houseCode + ' ' + h.houseName" :value="h.id" />
        </el-select>
        <div class="pen-grid">
          <div
            v-for="pen in sourcePens"
            :key="pen.id"
            class="pen-cell"
            :class="{ on: sourcePenId === pen.id }"
            @click="pickSourcePen(pen)"
          >
            <div class="pc-name">{{ pen.penCode }}</div>
            <div class="pc-count">{{ pen.pigCount }} <span class="pc-cap">/ {{ pen.capacity }} 头</span></div>
          </div>
        </div>
        <div v-if="sourcePenId" style="margin-top: 10px">
          <div class="section-title">栏内猪只</div>
          <div v-for="pig in sourcePigs" :key="pig.id" class="pig-row" :class="{ on: selected.includes(pig.id) }" @click="togglePig(pig)">
            <el-checkbox :model-value="selected.includes(pig.id)" @click.stop="togglePig(pig)" />
            <div>
              <div class="pig-ear">{{ pig.earTag }}</div>
              <div class="pig-sub">{{ pig.breedName }} · {{ pig.weightKg }}kg · {{ pig.statusName }}</div>
            </div>
          </div>
          <div v-if="!sourcePigs.length" class="empty-tip">该栏位暂无猪只</div>
        </div>
      </div>

      <!-- 中：搬移 -->
      <div class="move-mid">
        <div class="move-arrow">▶</div>
        <el-button type="primary" size="small" @click="move">搬移</el-button>
        <div class="tag">{{ sourcePen ? sourcePen.penCode : '源' }} → {{ targetPen ? targetPen.penCode : '目标' }}</div>
        <div class="tag" v-if="targetPen">目标余位 {{ remainOf(targetPen) }}</div>
        <el-button size="small" link @click="selected = sourcePigs.map((p) => p.id)">全选</el-button>
      </div>

      <!-- 右：目标栏位 -->
      <div class="side-pane" :class="{ 'on-right': targetPenId }">
        <div class="section-title">目标栏位（{{ targetPen ? targetPen.penCode : '未选择' }}）</div>
        <el-alert
          type="info"
          :closable="false"
          title="检修 / 待清空 / 停用栋舍，以及维修、清洗消毒中的栏位一致拒绝转入（后端强制）"
          style="margin-bottom: 8px"
        />
        <div class="pen-grid">
          <div
            v-for="pen in targetPens"
            :key="pen.id"
            class="pen-cell"
            :class="{ on: targetPenId === pen.id, blocked: !!penRestricted(pen) }"
            @click="!penRestricted(pen) && pickTargetPen(pen)"
          >
            <div class="pc-name">{{ pen.penCode }}</div>
            <div class="pc-count">{{ remainOf(pen) }} <span class="pc-cap">/ 余位</span></div>
            <div class="load-bar" :class="{ full: remainOf(pen) === 0 }">
              <i :style="{ width: Math.round(((pen.pigCount || 0) * 100) / (pen.capacity || 1)) + '%' }"></i>
            </div>
            <div class="pc-cap">
              {{ houseStatusNameOf(houseStatusMap[pen.houseId]?.status) }} · 存栏 {{ pen.pigCount }} / {{ pen.capacity }}
            </div>
            <div v-if="penRestricted(pen)" class="pen-flag">{{ penRestricted(pen) }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="grid-2" style="margin-top: 14px">
      <div class="panel">
        <div class="section-title">转栏记录（{{ transfers.length }}）</div>
        <el-table :data="transfers" size="small" max-height="240">
          <el-table-column prop="earTag" label="耳标号" width="140" />
          <el-table-column prop="fromPenCode" label="源栏位" width="120" />
          <el-table-column prop="toPenCode" label="目标栏位" width="120" />
          <el-table-column prop="transferDate" label="日期" width="110" />
          <el-table-column prop="reasonName" label="原因" width="100" />
          <el-table-column prop="operator" label="操作人" />
        </el-table>
      </div>
      <div class="panel">
        <div class="section-title">饲喂记录（{{ feedings.length }}）</div>
        <el-table :data="feedings" size="small" max-height="240">
          <el-table-column prop="penCode" label="栏位" width="120" />
          <el-table-column prop="feedName" label="饲料" />
          <el-table-column prop="amountKg" label="投料 kg" width="90" />
          <el-table-column prop="pigCount" label="头数" width="70" />
          <el-table-column prop="perHeadKg" label="kg/头" width="80" />
          <el-table-column prop="feedTime" label="时间" width="150" />
        </el-table>
      </div>
    </div>

    <el-dialog v-model="feedingDialog" title="登记投料" width="420px">
      <el-form :model="feedForm" label-width="90px">
        <el-form-item label="栏位">
          <el-select v-model="feedForm.penId" style="width: 100%">
            <el-option v-for="pen in pens" :key="pen.id" :label="pen.penCode" :value="pen.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="饲料批次">
          <el-select v-model="feedForm.feedBatchId" clearable style="width: 100%">
            <el-option v-for="fb in feedBatches" :key="fb.id" :label="fb.feedNo + ' ' + fb.feedName" :value="fb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="饲料名称"><el-input v-model="feedForm.feedName" placeholder="如 育肥配合料 552" /></el-form-item>
        <el-form-item label="饲料类型">
          <el-select v-model="feedForm.feedType" style="width: 100%">
            <el-option label="教槽料" value="CREEP" />
            <el-option label="保育料" value="NURSERY" />
            <el-option label="育肥料" value="FINISHER" />
            <el-option label="母猪料" value="SOW" />
            <el-option label="公猪料" value="BOAR" />
          </el-select>
        </el-form-item>
        <el-form-item label="投料量"><el-input-number v-model="feedForm.amountKg" :min="1" :max="500" :precision="2" /></el-form-item>
        <el-form-item label="头数"><el-input-number v-model="feedForm.pigCount" :min="0" /></el-form-item>
        <el-form-item label="投料人"><el-input v-model="feedForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feedingDialog = false">取消</el-button>
        <el-button type="primary" @click="submitFeeding">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
