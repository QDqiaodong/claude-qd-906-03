<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const TRACE_TYPES = [
  { value: 'IMMUNE', label: '免疫' },
  { value: 'MEDICATION', label: '用药' },
  { value: 'TRANSFER', label: '转栏' },
  { value: 'FEEDING', label: '饲喂' },
  { value: 'SALE', label: '出栏' },
  { value: 'DEATH', label: '死亡' },
  { value: 'CHECK', label: '检疫' }
]
const TARGET_TYPES = [
  { value: 'PIG', label: '猪只' },
  { value: 'BATCH', label: '出栏批次' },
  { value: 'HOUSE', label: '栋舍' },
  { value: 'FEED_BATCH', label: '饲料批次' }
]

const traces = ref([])
const vaccines = ref([])
const medications = ref([])
const pigs = ref([])
const typeFilter = ref('')

const traceDialog = ref(false)
const vaccineDialog = ref(false)
const medDialog = ref(false)
const attachDialog = ref(false)
const attachId = ref(null)

const traceForm = ref(emptyTrace())
const vaccineForm = ref({ pigId: null, vaccineName: '', doseMl: 2, vaccineDate: '', nextDate: '', operator: '周防疫员', remark: '' })
const medEditingId = ref(null)
const medForm = ref(emptyMedication())
const attachForm = ref({ targetType: 'PIG', targetId: null })
const optionCache = ref({})

function emptyTrace () {
  return {
    traceType: 'IMMUNE',
    eventTitle: '',
    eventDesc: '',
    operator: '驻场兽医',
    targets: [{ targetType: 'PIG', targetId: null }]
  }
}

function emptyMedication () {
  return {
    pigId: null,
    medicineName: '',
    dose: '',
    medicationDate: new Date().toISOString().slice(0, 10),
    withdrawalDays: 7,
    operator: '赵德明',
    remark: '',
    requestNo: makeMedicationRequestNo()
  }
}

function makeMedicationRequestNo () {
  if (window.crypto?.randomUUID) return `MED-${crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase()}`
  return `MED-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`.toUpperCase()
}

const filtered = computed(() => typeFilter.value
  ? traces.value.filter((t) => t.traceType === typeFilter.value)
  : traces.value)

async function load () {
  traces.value = await api.trace.list()
  vaccines.value = await api.trace.vaccines()
  medications.value = await api.medication.list()
  pigs.value = await api.pig.options()
}

async function optionsOf (type) {
  if (!optionCache.value[type]) {
    optionCache.value[type] = await api.trace.targetOptions(type)
  }
  return optionCache.value[type] || []
}

const allOptions = computed(() => optionCache.value)

async function ensureOptions () {
  for (const t of TARGET_TYPES) await optionsOf(t.value)
}

function addTargetRow () {
  traceForm.value.targets.push({ targetType: 'PIG', targetId: null })
}

function removeTargetRow (idx) {
  traceForm.value.targets.splice(idx, 1)
}

async function submitTrace () {
  const targets = traceForm.value.targets.filter((t) => t.targetId)
  if (!targets.length) {
    ElMessage.warning('溯源记录必须至少挂一个来源对象')
    return
  }
  try {
    await api.trace.create({ ...traceForm.value, targets })
    ElMessage.success('溯源记录已创建')
    traceDialog.value = false
    traceForm.value = emptyTrace()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openAttach (trace) {
  attachId.value = trace.id
  attachForm.value = { targetType: 'PIG', targetId: null }
  attachDialog.value = true
}

async function submitAttach () {
  try {
    await api.trace.attach(attachId.value, attachForm.value)
    ElMessage.success('来源对象已追加')
    attachDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitVaccine () {
  try {
    await api.trace.createVaccine(vaccineForm.value)
    ElMessage.success('免疫记录已登记')
    vaccineDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openMedication (row) {
  medEditingId.value = row?.id || null
  medForm.value = row ? {
    medicineName: row.medicineName,
    dose: row.dose || '',
    medicationDate: row.medicationDate,
    withdrawalDays: row.withdrawalDays,
    operator: row.operator || '',
    remark: row.remark || '',
    requestNo: makeMedicationRequestNo()
  } : emptyMedication()
  if (row) medForm.value.pigId = row.pigId
  medDialog.value = true
}

async function submitMedication () {
  try {
    if (medEditingId.value) {
      await api.medication.update(medEditingId.value, medForm.value)
      ElMessage.success('用药记录已纠错，休药期已重算')
    } else {
      if (!medForm.value.pigId) {
        ElMessage.warning('请选择猪只')
        return
      }
      await api.medication.create({ ...medForm.value, pigIds: [medForm.value.pigId] })
      ElMessage.success('用药记录已登记，休药期已重算')
    }
    medDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function revokeMedication (row) {
  try {
    const { value } = await ElMessageBox.prompt('请填写撤销原因', `撤销 ${row.earTag} 的用药记录`, {
      confirmButtonText: '确认撤销',
      cancelButtonText: '取消',
      inputValue: '登记错误',
      inputValidator: (text) => (text && text.trim()) || '撤销原因不能为空'
    })
    await api.medication.revoke(row.id, { reason: value, operator: row.operator || '场长' })
    ElMessage.success('用药记录已撤销，休药期已重算')
    load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '撤销失败')
  }
}

onMounted(async () => {
  await load()
  await ensureOptions()
})
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">防疫免疫与溯源</span>
      <span class="view-tip">时间线 + 多态来源标签（@ManyToAny：猪只 / 出栏批次 / 栋舍 / 饲料批次 任意组合）</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: typeFilter === '' }" @click="typeFilter = ''">全部事件</span>
      <span v-for="t in TRACE_TYPES" :key="t.value" class="chip" :class="{ on: typeFilter === t.value }" @click="typeFilter = t.value">{{ t.label }}</span>
      <el-button size="small" type="primary" style="margin-left: auto" @click="traceDialog = true">新建溯源记录</el-button>
    </div>

    <div class="grid-2" style="grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr)">
      <!-- 时间线 -->
      <div class="panel">
        <div class="section-title">溯源时间线（{{ filtered.length }}）</div>
        <div class="timeline">
          <div v-for="trace in filtered" :key="trace.id" class="tl-node">
            <div class="tl-card">
              <div style="display: flex; align-items: center; gap: 8px">
                <span class="tag solid" :style="{ background: trace.traceTypeColor }">{{ trace.traceTypeName }}</span>
                <span class="tl-title">{{ trace.eventTitle }}</span>
                <span class="tl-time" style="margin-left: auto">{{ trace.eventTime }}</span>
              </div>
              <div class="tl-desc">{{ trace.eventDesc }}</div>
              <div>
                <span v-for="t in trace.targets" :key="t.targetType + t.targetId" class="target-chip">
                  {{ t.targetTypeName }}：<b>{{ t.targetCode }}</b>
                </span>
                <span v-if="!trace.targets.length" class="target-chip">未挂来源对象</span>
              </div>
              <div style="display: flex; align-items: center; gap: 8px; margin-top: 4px">
                <span class="tl-time">{{ trace.traceNo }} · 记录人 {{ trace.operator || '—' }}</span>
                <el-button size="small" link style="margin-left: auto" @click="openAttach(trace)">+ 追加来源</el-button>
              </div>
            </div>
          </div>
          <div v-if="!filtered.length" class="empty-tip">暂无溯源记录</div>
        </div>
      </div>

      <!-- 免疫 -->
      <div class="panel">
        <div class="section-title">
          免疫记录（{{ vaccines.length }}）
          <el-button size="small" type="primary" style="margin-left: auto" @click="vaccineDialog = true">登记免疫</el-button>
        </div>
        <el-table :data="vaccines" size="small" max-height="260">
          <el-table-column prop="earTag" label="耳标号" width="130" />
          <el-table-column prop="vaccineName" label="疫苗" />
          <el-table-column prop="doseMl" label="剂量 ml" width="78" />
          <el-table-column prop="vaccineDate" label="免疫日" width="105" />
          <el-table-column prop="nextDate" label="下次" width="105" />
          <el-table-column label="来源计划" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.planCode" size="small" type="success">{{ row.planCode }} · 第 {{ row.doseNo }} 剂</el-tag>
              <span v-else style="font-size: 12px; color: var(--ink-dim)">手工登记</span>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="免疫员" width="85" />
        </el-table>

        <div class="section-title" style="margin-top: 16px">
          用药与休药期（{{ medications.length }}，含已撤销）
          <el-button size="small" type="warning" style="margin-left: auto" @click="openMedication()">登记用药</el-button>
        </div>
        <el-table :data="medications" size="small" max-height="280">
          <el-table-column prop="earTag" label="耳标号" width="130" />
          <el-table-column prop="medicineName" label="药品" width="120" />
          <el-table-column prop="medicationDate" label="用药日" width="105" />
          <el-table-column prop="withdrawalDays" label="休药天" width="70" />
          <el-table-column prop="deadline" label="截止日" width="105" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.revoked ? 'info' : row.restrictedNow ? 'danger' : 'success'">
                {{ row.revoked ? '已撤销' : row.restrictedNow ? '限制中' : '可出栏' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="经办人" width="85" />
          <el-table-column label="操作" width="105" fixed="right">
            <template #default="{ row }">
              <template v-if="!row.revoked">
                <el-button size="small" link @click="openMedication(row)">纠错</el-button>
                <el-button size="small" link type="danger" @click="revokeMedication(row)">撤销</el-button>
              </template>
              <span v-else style="font-size: 12px; color: var(--ink-dim)">保留审计</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新建溯源 -->
    <el-dialog v-model="traceDialog" title="新建溯源记录" width="520px">
      <el-form :model="traceForm" label-width="90px">
        <el-form-item label="事件类型">
          <el-select v-model="traceForm.traceType" style="width: 100%">
            <el-option v-for="t in TRACE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="事件标题"><el-input v-model="traceForm.eventTitle" /></el-form-item>
        <el-form-item label="事件描述"><el-input v-model="traceForm.eventDesc" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="记录人"><el-input v-model="traceForm.operator" /></el-form-item>
        <el-form-item label="来源对象">
          <div style="width: 100%">
            <div v-for="(row, idx) in traceForm.targets" :key="idx" style="display: flex; gap: 6px; margin-bottom: 6px">
              <el-select v-model="row.targetType" size="small" style="width: 120px">
                <el-option v-for="t in TARGET_TYPES" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
              <el-select v-model="row.targetId" size="small" style="flex: 1 1 auto" placeholder="选择来源对象">
                <el-option v-for="o in (allOptions[row.targetType] || [])" :key="o.targetId" :label="o.targetCode + ' ' + (o.targetName || '')" :value="o.targetId" />
              </el-select>
              <el-button size="small" @click="removeTargetRow(idx)">删</el-button>
            </div>
            <el-button size="small" link type="primary" @click="addTargetRow">+ 再加一个来源对象</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="traceDialog = false">取消</el-button>
        <el-button type="primary" @click="submitTrace">保存</el-button>
      </template>
    </el-dialog>

    <!-- 追加来源 -->
    <el-dialog v-model="attachDialog" title="追加多态来源对象" width="420px">
      <el-form label-width="90px">
        <el-form-item label="来源类型">
          <el-select v-model="attachForm.targetType" style="width: 100%">
            <el-option v-for="t in TARGET_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源对象">
          <el-select v-model="attachForm.targetId" style="width: 100%">
            <el-option v-for="o in (allOptions[attachForm.targetType] || [])" :key="o.targetId" :label="o.targetCode + ' ' + (o.targetName || '')" :value="o.targetId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="attachDialog = false">取消</el-button>
        <el-button type="primary" @click="submitAttach">追加</el-button>
      </template>
    </el-dialog>

    <!-- 登记免疫 -->
    <el-dialog v-model="vaccineDialog" title="登记免疫" width="420px">
      <el-form :model="vaccineForm" label-width="90px">
        <el-form-item label="猪只">
          <el-select v-model="vaccineForm.pigId" style="width: 100%">
            <el-option v-for="p in pigs" :key="p.id" :label="p.earTag + '（' + p.statusName + '）'" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="疫苗名称"><el-input v-model="vaccineForm.vaccineName" placeholder="如 猪瘟活疫苗" /></el-form-item>
        <el-form-item label="剂量 ml"><el-input-number v-model="vaccineForm.doseMl" :min="0.1" :max="20" :precision="2" /></el-form-item>
        <el-form-item label="免疫日期">
          <el-date-picker v-model="vaccineForm.vaccineDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="下次免疫">
          <el-date-picker v-model="vaccineForm.nextDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="免疫员"><el-input v-model="vaccineForm.operator" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vaccineDialog = false">取消</el-button>
        <el-button type="primary" @click="submitVaccine">保存</el-button>
      </template>
    </el-dialog>
    <!-- 登记 / 纠错用药 -->
    <el-dialog v-model="medDialog" :title="medEditingId ? '纠错用药记录' : '登记用药与休药期'" width="440px">
      <el-form :model="medForm" label-width="100px">
        <el-form-item v-if="!medEditingId" label="猪只">
          <el-select v-model="medForm.pigId" filterable style="width: 100%">
            <el-option
              v-for="p in pigs.filter((item) => !['SOLD', 'DEAD', 'CULLED'].includes(item.status))"
              :key="p.id"
              :label="p.earTag + '（' + p.statusName + ' / ' + (p.penCode || '无栏位') + '）'"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="药品名称"><el-input v-model="medForm.medicineName" /></el-form-item>
        <el-form-item label="剂量"><el-input v-model="medForm.dose" placeholder="如 2g/头" /></el-form-item>
        <el-form-item label="用药日期">
          <el-date-picker v-model="medForm.medicationDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="休药天数"><el-input-number v-model="medForm.withdrawalDays" :min="0" :max="365" /></el-form-item>
        <el-form-item label="经办人"><el-input v-model="medForm.operator" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="medForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="medDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMedication">{{ medEditingId ? '保存纠错' : '保存登记' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
