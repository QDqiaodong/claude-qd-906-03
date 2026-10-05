<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const COLUMNS = [
  { value: 'BUILDING', label: '在建' },
  { value: 'RAISING', label: '在养' },
  { value: 'SELLING', label: '出栏中' },
  { value: 'CLEARED', label: '已清栏' }
]
const BREEDS = [
  { value: 'YORKSHIRE', label: '大白' },
  { value: 'LANDRACE', label: '长白' },
  { value: 'DUROC', label: '杜洛克' },
  { value: 'PIETRAIN', label: '皮特兰' },
  { value: 'BERKSHIRE', label: '巴克夏' }
]
const MODES = [
  {
    value: 'PARTIAL',
    name: '合格猪只先出栏',
    desc: '默认推荐。先放行休药期合格猪只，减少压栏；受限猪只继续留栏。'
  },
  {
    value: 'ALL_OR_NONE',
    name: '整批拦截更易核对',
    desc: '只要有一头猪仍在休药期，整批都不放行，适合收购方按整批验收。'
  }
]

const batches = ref([])
const houses = ref([])
const dialog = ref(false)
const shipDialog = ref(false)
const shipSaving = ref(false)
const currentBatch = ref(null)
const medDialogVisible = ref(false)
const medBatch = ref(null)
const medSaving = ref(false)
const review = ref(null)
const form = ref(emptyForm())
const shipForm = ref(emptyShipForm())
const shipCount = ref({})
const medForm = ref(emptyBatchMedication())
const selectedPigIds = ref([])

function emptyForm () {
  return {
    batchNo: '', batchName: '', houseId: null, breed: 'LANDRACE', intakeCount: 100,
    startDate: '', expectDate: '', buyer: '', avgWeightKg: null, note: ''
  }
}

function emptyShipForm () {
  return {
    releaseMode: 'PARTIAL',
    shipDate: new Date().toISOString().slice(0, 10),
    count: null,
    operator: '',
    buyer: '',
    requestNo: ''
  }
}

function emptyBatchMedication () {
  return {
    medicineName: '', dose: '',
    medicationDate: new Date().toISOString().slice(0, 10),
    withdrawalDays: 7,
    operator: '',
    remark: '',
    requestNo: makeRequestNo('MED')
  }
}

const grouped = computed(() => {
  const map = {}
  for (const col of COLUMNS) map[col.value] = []
  for (const b of batches.value) {
    if (map[b.status]) map[b.status].push(b)
  }
  return map
})

const summary = computed(() => {
  let intake = 0
  let sold = 0
  for (const b of batches.value) {
    intake += b.intakeCount
    sold += b.soldCount
  }
  return { intake, sold, rate: intake ? Math.round((sold * 100) / intake) : 0 }
})

const selectedEligibleSet = computed(() => new Set(selectedPigIds.value))
const chosenIds = computed(() => new Set((review.value?.releasedPigs || []).map((p) => p.id)))
const eligibleCandidates = computed(() => (review.value?.eligiblePigs || []).filter((p) => !chosenIds.value.has(p.id)))

async function load () {
  batches.value = await api.batch.list()
  if (!houses.value.length) houses.value = await api.house.list()
}

async function advance (batch) {
  try {
    await api.batch.advance(batch.id)
    ElMessage.success(`${batch.batchNo} 已推进到${batch.nextStatusName}`)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openShip (batch) {
  currentBatch.value = batch
  shipForm.value = { ...emptyShipForm(), buyer: batch.buyer || '' }
  selectedPigIds.value = []
  review.value = null
  shipDialog.value = true
  await refreshPreview()
}

function changeMode (value) {
  shipForm.value.releaseMode = value
  shipForm.value.count = null
  selectedPigIds.value = []
  refreshPreview()
}

async function refreshPreview () {
  if (!currentBatch.value) return
  try {
    const payload = buildPayload(false)
    review.value = await api.batch.shipmentPreview(currentBatch.value.id, payload)
    if (shipForm.value.count === null || shipForm.value.count === undefined) {
      shipForm.value.count = review.value.eligibleCount
    }
  } catch (e) {
    review.value = null
    ElMessage.error(e.message)
  }
}

function buildPayload (confirming) {
  const payload = {
    releaseMode: shipForm.value.releaseMode,
    shipDate: shipForm.value.shipDate,
    count: shipForm.value.count,
    operator: shipForm.value.operator,
    buyer: shipForm.value.buyer
  }
  if (selectedPigIds.value.length) payload.pigIds = [...selectedPigIds.value]
  if (confirming) {
    shipForm.value.requestNo = shipForm.value.requestNo || makeRequestNo('SHIP')
    payload.requestNo = shipForm.value.requestNo
  }
  return payload
}

async function confirmShip () {
  if (!review.value?.canSubmit) {
    ElMessage.warning(review.value?.message || '当前条件不能提交出栏')
    return
  }
  shipSaving.value = true
  try {
    const result = await api.batch.shipmentConfirm(currentBatch.value.id, buildPayload(true))
    shipForm.value.requestNo = result.requestNo
    ElMessage.success(result.message || '出栏放行已完成')
    shipDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    shipSaving.value = false
  }
}

function togglePig (pig) {
  const idx = selectedPigIds.value.indexOf(pig.id)
  if (idx >= 0) selectedPigIds.value.splice(idx, 1)
  else selectedPigIds.value.push(pig.id)
  refreshPreview()
}

function openBatchMedication (batch) {
  medBatch.value = batch
  medForm.value = emptyBatchMedication()
  medDialogVisible.value = true
}

async function submitBatchMedication () {
  medSaving.value = true
  try {
    const rows = await api.medication.create({
      ...medForm.value,
      batchId: medBatch.value.id
    })
    ElMessage.success(`已为 ${rows.length} 头猪登记用药，批次休药期已重算`)
    medDialogVisible.value = false
    medBatch.value = null
    load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    medSaving.value = false
  }
}

async function legacyShip (batch) {
  try {
    await api.batch.ship(batch.id, shipCount.value[batch.id])
    ElMessage.success(`${batch.batchNo} 历史批次头数登记 ${shipCount.value[batch.id]} 头`)
    shipCount.value[batch.id] = null
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submit () {
  try {
    await api.batch.create(form.value)
    ElMessage.success('出栏批次已创建')
    dialog.value = false
    form.value = emptyForm()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeBatch (batch) {
  await ElMessageBox.confirm(`确认删除 ${batch.batchNo}？`, '删除批次', { type: 'warning' })
  await api.batch.remove(batch.id)
  ElMessage.success('批次已删除')
  load()
}

function makeRequestNo (prefix) {
  if (window.crypto?.randomUUID) return `${prefix}-${crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`.toUpperCase()
}

function withdrawalText (batch) {
  if (batch.restrictedPigCount > 0) return `${batch.restrictedPigCount} 头休药中 · ${batch.earliestShipDate} 起可出栏`
  if (batch.latestWithdrawalDeadline) return `休药已满 · ${batch.earliestShipDate} 起可出栏`
  return '暂无有效用药记录'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">出栏批次</span>
      <span class="view-tip">猪只级出栏放行：提交前核对合格放行、休药期拦截、状态异常跳过，数量 / 状态 / 批次累计同步提交</span>
    </div>

    <div class="chip-bar">
      <span class="chip on">入栏合计 {{ summary.intake }} 头</span>
      <span class="chip">已出栏 {{ summary.sold }} 头</span>
      <span class="chip">出栏进度 {{ summary.rate }}%</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="dialog = true">新建批次</el-button>
    </div>

    <div class="pipeline">
      <div v-for="col in COLUMNS" :key="col.value" class="pipe-col">
        <div class="pipe-col-head">
          <span class="pipe-col-title">{{ col.label }}</span>
          <span class="tag">{{ grouped[col.value].length }} 批</span>
        </div>

        <div v-for="batch in grouped[col.value]" :key="batch.id" class="batch-card">
          <div class="bc-no">{{ batch.batchNo }}</div>
          <div class="bc-name">{{ batch.batchName }} · {{ batch.houseCode || '未指定栋舍' }} · {{ batch.breedName }}</div>
          <div class="bc-nums">
            <span>入栏 <b>{{ batch.intakeCount }}</b></span>
            <span>已出栏 <b>{{ batch.soldCount }}</b></span>
            <span>死淘 <b>{{ batch.deadCount }}</b></span>
            <span>台账在群 <b>{{ batch.activePigCount }}</b></span>
          </div>
          <div class="load-bar" :class="{ full: batch.soldRate >= 1 }">
            <i :style="{ width: Math.round(batch.soldRate * 100) + '%' }"></i>
          </div>
          <div class="withdrawal-banner" :class="{ blocked: batch.restrictedPigCount > 0, clear: batch.restrictedPigCount === 0 && batch.latestWithdrawalDeadline }">
            <span class="tag solid" :style="{ background: batch.restrictedPigCount > 0 ? 'var(--hog-red)' : 'var(--hog-green)' }">休药期</span>
            <span style="flex: 1 1 auto">{{ withdrawalText(batch) }}</span>
            <el-button size="small" link type="warning" @click="openBatchMedication(batch)">批量用药</el-button>
          </div>
          <div class="bc-name" style="margin-top: 6px">
            均重 {{ batch.avgWeightKg || '—' }}kg · 预计 {{ batch.expectDate || '—' }} · {{ batch.buyer || '未定收购方' }}
          </div>

          <div v-if="batch.status === 'SELLING'" class="bc-foot" style="margin-top: 10px">
            <el-button v-if="batch.activePigCount > 0" type="primary" size="small" style="width: 100%" @click="openShip(batch)">
              猪只级出栏放行
            </el-button>
            <div v-else style="display: flex; gap: 6px">
              <el-input-number v-model="shipCount[batch.id]" :min="1" :max="batch.remainCount" size="small" style="width: 126px" placeholder="头数" />
              <el-button type="primary" size="small" style="width: 100%" @click="legacyShip(batch)">历史头数登记</el-button>
            </div>
          </div>

          <!-- 卡片底部单按钮推进状态 -->
          <div class="bc-foot">
            <el-button v-if="batch.nextStatus" type="primary" size="small" style="width: 100%" @click="advance(batch)">
              推进到「{{ batch.nextStatusName }}」
            </el-button>
            <el-button v-else size="small" style="width: 100%" disabled>已清栏 · 终态</el-button>
          </div>
          <div style="text-align: right; margin-top: 6px">
            <el-button size="small" link type="danger" @click="removeBatch(batch)">删除</el-button>
          </div>
        </div>

        <div v-if="!grouped[col.value].length" class="empty-tip">暂无批次</div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建出栏批次" width="440px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="批次号"><el-input v-model="form.batchNo" placeholder="如 BATCH-2609-03" /></el-form-item>
        <el-form-item label="批次名称"><el-input v-model="form.batchName" placeholder="如 九月育肥一批" /></el-form-item>
        <el-form-item label="所在栋舍">
          <el-select v-model="form.houseId" clearable style="width: 100%">
            <el-option v-for="h in houses" :key="h.id" :label="h.houseCode + ' ' + h.houseName" :value="h.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="品种">
          <el-select v-model="form.breed" style="width: 100%">
            <el-option v-for="b in BREEDS" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="入栏头数"><el-input-number v-model="form.intakeCount" :min="1" :max="5000" /></el-form-item>
        <el-form-item label="入栏日期">
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="预计出栏">
          <el-date-picker v-model="form.expectDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="收购方"><el-input v-model="form.buyer" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shipDialog" :title="`出栏放行 · ${currentBatch?.batchNo || ''}`" width="920px" top="4vh">
      <div v-if="review" class="ship-layout">
        <el-alert
          :title="review.message"
          :type="review.canSubmit ? 'success' : 'warning'"
          :closable="false"
          show-icon
          style="margin-bottom: 10px"
        />
        <div class="ship-controls">
          <div class="ship-mode-grid">
            <div
              v-for="mode in MODES"
              :key="mode.value"
              class="ship-mode"
              :class="{ on: shipForm.releaseMode === mode.value }"
              @click="changeMode(mode.value)"
            >
              <b>{{ mode.name }}</b>
              <span>{{ mode.desc }}</span>
            </div>
          </div>
          <el-form label-width="78px" :inline="true" size="small">
            <el-form-item label="出栏日期">
              <el-date-picker v-model="shipForm.shipDate" type="date" value-format="YYYY-MM-DD" @change="refreshPreview" />
            </el-form-item>
            <el-form-item label="操作员"><el-input v-model="shipForm.operator" placeholder="操作员" /></el-form-item>
            <el-form-item label="收购方"><el-input v-model="shipForm.buyer" /></el-form-item>
            <el-form-item v-if="shipForm.releaseMode === 'PARTIAL' && !selectedPigIds.length" label="放行头数">
              <el-input-number v-model="shipForm.count" :min="0" :max="review.eligibleCount" @change="refreshPreview" />
            </el-form-item>
          </el-form>
          <div class="selection-rule">
            <b>自动选取规则：</b>{{ review.selectionRule || '不指定猪只时，按台账固定 id 升序选择合格猪；选择后不自动补选。' }}
          </div>
        </div>

        <div class="ship-summary">
          <div class="ship-stat ok">
            <b>{{ review.releasedPigs.length }}</b><span>将放行</span>
            <small>合格可出栏</small>
          </div>
          <div class="ship-stat blocked">
            <b>{{ review.blockedCount }}</b><span>休药期拦下</span>
            <small>留栏重算</small>
          </div>
          <div class="ship-stat skipped">
            <b>{{ review.skippedCount }}</b><span>状态异常跳过</span>
            <small>不扣批次数量</small>
          </div>
          <div class="ship-stat">
            <b>{{ review.batchSoldCount + review.releasedPigs.length }}/{{ review.batchIntakeCount }}</b>
            <span>提交后累计</span>
            <small>批次入栏头数</small>
          </div>
        </div>

        <el-tabs>
          <el-tab-pane :label="`将放行（${review.releasedPigs.length}）`" name="released">
            <el-table :data="review.releasedPigs" size="small" max-height="250">
              <el-table-column label="选择" width="58">
                <template #default="{ row }">
                  <el-checkbox :model-value="selectedEligibleSet.has(row.id)" @change="togglePig(row)" />
                </template>
              </el-table-column>
              <el-table-column prop="earTag" label="耳标号" width="160" />
              <el-table-column prop="penCode" label="栏位" width="130" />
              <el-table-column prop="earliestShipDate" label="可出栏日期" width="130" />
              <el-table-column prop="reason" label="说明" />
              <el-table-column label="来源" width="110">
                <template #default="{ row }"><el-tag size="small" :type="row.autoSelected ? 'warning' : 'success'">{{ row.autoSelected ? '自动选取' : '人工勾选' }}</el-tag></template>
              </el-table-column>
            </el-table>
            <div v-if="!review.releasedPigs.length" class="empty-tip">没有将被放行的猪只</div>
          </el-tab-pane>
          <el-tab-pane :label="`其他合格候选（${eligibleCandidates.length}）`" name="eligible">
            <el-table :data="eligibleCandidates" size="small" max-height="250">
              <el-table-column label="选择" width="58">
                <template #default="{ row }">
                  <el-checkbox :model-value="selectedEligibleSet.has(row.id)" @change="togglePig(row)" />
                </template>
              </el-table-column>
              <el-table-column prop="earTag" label="耳标号" width="160" />
              <el-table-column prop="penCode" label="栏位" width="130" />
              <el-table-column prop="earliestShipDate" label="可出栏日期" width="130" />
              <el-table-column prop="reason" label="说明" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`休药期拦截（${review.blockedCount}）`" name="blocked">
            <el-table :data="review.blockedPigs" size="small" max-height="250">
              <el-table-column prop="earTag" label="耳标号" width="160" />
              <el-table-column prop="penCode" label="栏位" width="130" />
              <el-table-column prop="deadline" label="休药截止日" width="130" />
              <el-table-column prop="earliestShipDate" label="最晚可出栏次日" width="150" />
              <el-table-column prop="reason" label="拦截原因" />
            </el-table>
            <div v-if="!review.blockedPigs.length" class="empty-tip">没有被休药期拦截的猪只</div>
          </el-tab-pane>
          <el-tab-pane :label="`状态异常跳过（${review.skippedCount}）`" name="skipped">
            <el-table :data="review.skippedPigs" size="small" max-height="250">
              <el-table-column prop="earTag" label="耳标号" width="160" />
              <el-table-column prop="statusName" label="状态" width="100" />
              <el-table-column prop="reason" label="原因" />
            </el-table>
            <div v-if="!review.skippedPigs.length" class="empty-tip">没有状态异常猪只</div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <template #footer>
        <el-button @click="shipDialog = false">取消</el-button>
        <el-button @click="refreshPreview">重新预检</el-button>
        <el-button type="primary" :disabled="!review?.canSubmit" :loading="shipSaving" @click="confirmShip">
          确认放行 {{ review?.releasedPigs?.length || 0 }} 头
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="medDialogVisible" :title="`整批用药登记 · ${medBatch?.batchNo || ''}`" width="460px">
      <el-alert
        title="将自动选择该批次当前未出栏、未死亡、未淘汰的猪只；终态猪只不能补记用药。"
        type="warning"
        :closable="false"
        style="margin-bottom: 12px"
      />
      <el-form :model="medForm" label-width="100px">
        <el-form-item label="药品名称" required><el-input v-model="medForm.medicineName" placeholder="如 阿莫西林" /></el-form-item>
        <el-form-item label="剂量"><el-input v-model="medForm.dose" placeholder="如 2g/头" /></el-form-item>
        <el-form-item label="用药日期" required>
          <el-date-picker v-model="medForm.medicationDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="休药天数" required><el-input-number v-model="medForm.withdrawalDays" :min="0" :max="365" /></el-form-item>
        <el-form-item label="经办人" required><el-input v-model="medForm.operator" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="medForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="medDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="medSaving" @click="submitBatchMedication">保存整批用药</el-button>
      </template>
    </el-dialog>
  </div>
</template>
