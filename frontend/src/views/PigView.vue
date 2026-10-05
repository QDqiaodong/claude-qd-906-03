<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const BREEDS = [
  { value: 'YORKSHIRE', label: '大白' },
  { value: 'LANDRACE', label: '长白' },
  { value: 'DUROC', label: '杜洛克' },
  { value: 'PIETRAIN', label: '皮特兰' },
  { value: 'BERKSHIRE', label: '巴克夏' }
]
const STATUSES = [
  { value: 'ON_FARM', label: '在场' },
  { value: 'TRANSFERRED', label: '转栏' },
  { value: 'SOLD', label: '出栏' },
  { value: 'DEAD', label: '死亡' },
  { value: 'CULLED', label: '淘汰' }
]

const pigs = ref([])
const pens = ref([])
const houses = ref([])
const statusFilter = ref('')
const keyword = ref('')
const selected = ref([])
const batchOpen = ref(false)
const dialog = ref(false)
const form = ref(emptyForm())
const editingId = ref(null)
const batchStatus = ref('SOLD')
const batchPenId = ref(null)
const medDialog = ref(false)
const medSaving = ref(false)
const medForm = ref(emptyMedication())
const immuneDialog = ref(false)
const immunePig = ref(null)
const immuneTasks = ref([])

// 猪只详情追溯：该猪只的免疫计划任务（含计划、剂次、形成依据）
async function openImmune (pig) {
  immunePig.value = pig
  immuneDialog.value = true
  immuneTasks.value = []
  try {
    immuneTasks.value = await api.immune.tasks(pig.id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function emptyMedication () {
  return {
    medicineName: '',
    dose: '',
    medicationDate: new Date().toISOString().slice(0, 10),
    withdrawalDays: 7,
    operator: '',
    remark: '',
    requestNo: makeRequestNo('MED')
  }
}

function emptyForm () {
  return {
    earTag: '', breed: 'YORKSHIRE', gender: 'FEMALE', birthDate: '', sourceType: 'SELF',
    penId: null, weightKg: null, note: ''
  }
}

const filtered = computed(() => pigs.value.filter((p) => {
  if (statusFilter.value && p.status !== statusFilter.value) return false
  if (keyword.value && !p.earTag.includes(keyword.value.trim())) return false
  return true
}))

const selectedPigs = computed(() => pigs.value.filter((p) => selected.value.includes(p.id)))

async function load () {
  pigs.value = await api.pig.list()
  pens.value = await api.house.pens()
  houses.value = await api.house.list()
}

// 栏位所在栋舍是否受限：检修 / 待清空 / 停用一致拒绝新建入栏、转入（后端强制，前端一致提示）
const HOUSE_STATUS_NAMES = { ACTIVE: '在用', MAINTAIN: '检修', PENDING_DISABLE: '待清空', DISABLED: '停用' }
function houseOfPen (pen) {
  return houses.value.find((h) => h.id === pen?.houseId) || null
}
function penBlockedReason (pen) {
  const h = houseOfPen(pen)
  if (!h) return ''
  if (h.status === 'MAINTAIN') return '栋舍检修中·冻结新增'
  if (h.status === 'PENDING_DISABLE') return '栋舍待清空·只准迁出'
  if (h.status === 'DISABLED') return '栋舍已停用'
  if (pen.status === 'REPAIR') return '栏位维修中'
  if (pen.status === 'CLEANING') return '栏位清洗消毒中'
  return ''
}
function penLabel (pen) {
  const h = houseOfPen(pen)
  const houseTag = h ? `[${HOUSE_STATUS_NAMES[h.status] || h.status}] ` : ''
  const blocked = penBlockedReason(pen)
  return houseTag + pen.penCode + '（余 ' + Math.max(0, (pen.capacity || 0) - (pen.pigCount || 0)) + '）' + (blocked ? ' · ' + blocked : '')
}

function toggle (pig) {
  const idx = selected.value.indexOf(pig.id)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
  } else {
    selected.value.push(pig.id)
    if (!batchOpen.value) batchOpen.value = true
  }
}

function closeBatch () {
  batchOpen.value = false
  selected.value = []
}

async function runBatchStatus () {
  try {
    const n = await api.pig.batchStatus(selected.value, batchStatus.value)
    ElMessage.success(`批量${statusName(batchStatus.value)}完成，共 ${n} 头`)
    closeBatch()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function runBatchTransfer () {
  if (!batchPenId.value) {
    ElMessage.warning('请选择目标栏位')
    return
  }
  const target = pens.value.find((p) => p.id === batchPenId.value)
  const blockedReason = penBlockedReason(target)
  if (blockedReason) {
    ElMessage.error(`目标栏位不可转入：${blockedReason}`)
    return
  }
  try {
    const list = await api.transfer.create({ pigIds: selected.value, toPenId: batchPenId.value, reason: 'DENSITY' })
    ElMessage.success(`批量转栏完成，共 ${list.length} 头`)
    closeBatch()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function changeOne (pig, status) {
  try {
    await api.pig.status(pig.id, status)
    ElMessage.success(`${pig.earTag} 已${statusName(status)}`)
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitMedication () {
  if (!selected.value.length) {
    ElMessage.warning('请先勾选猪只')
    return
  }
  medSaving.value = true
  try {
    const rows = await api.medication.create({
      ...medForm.value,
      pigIds: selected.value
    })
    ElMessage.success(`用药登记已保存 ${rows.length} 头，休药期已重算`)
    medDialog.value = false
    closeBatch()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    medSaving.value = false
  }
}

function openMedication () {
  medForm.value = emptyMedication()
  medDialog.value = true
}

function makeRequestNo (prefix) {
  if (window.crypto?.randomUUID) return `${prefix}-${crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase()}`
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`.toUpperCase()
}

function statusName (v) {
  const hit = STATUSES.find((s) => s.value === v)
  return hit ? hit.label : v
}

function openCreate () {
  editingId.value = null
  form.value = emptyForm()
  dialog.value = true
}

function openEdit (pig) {
  editingId.value = pig.id
  form.value = {
    earTag: pig.earTag, breed: pig.breed, gender: pig.gender, birthDate: pig.birthDate,
    sourceType: pig.sourceType, penId: pig.penId, weightKg: pig.weightKg, note: pig.note
  }
  dialog.value = true
}

async function submit () {
  try {
    if (editingId.value) {
      await api.pig.update(editingId.value, form.value)
      ElMessage.success('猪只已更新')
    } else {
      await api.pig.create(form.value)
      ElMessage.success('猪只已入栏')
    }
    dialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removePig (pig) {
  await ElMessageBox.confirm(`确认删除 ${pig.earTag}？`, '删除猪只', { type: 'warning' })
  await api.pig.remove(pig.id)
  ElMessage.success('猪只已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">猪只个体与耳标</span>
      <span class="view-tip">耳标列表 + 侧滑批量操作（勾选后右侧滑出批量面板）</span>
    </div>

    <div class="chip-bar">
      <span class="chip" :class="{ on: statusFilter === '' }" @click="statusFilter = ''">全部</span>
      <span v-for="s in STATUSES" :key="s.value" class="chip" :class="{ on: statusFilter === s.value }" @click="statusFilter = s.value">{{ s.label }}</span>
      <el-input v-model="keyword" size="small" placeholder="按耳标号搜索" style="width: 180px; margin-left: 10px" />
      <el-button size="small" type="primary" style="margin-left: auto" @click="openCreate">新建猪只</el-button>
    </div>

    <div class="grid-2" style="grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr)">
      <div class="panel">
        <div class="section-title">耳标台账（{{ filtered.length }}）</div>
        <div v-for="pig in filtered" :key="pig.id" class="pig-row" :class="{ on: selected.includes(pig.id) }">
          <el-checkbox :model-value="selected.includes(pig.id)" @click.stop="toggle(pig)" />
          <div style="flex: 1 1 auto; min-width: 0" @click="toggle(pig)">
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">
              {{ pig.breedName }} · {{ pig.genderName }} · {{ pig.ageInDays }} 日龄 ·
              {{ pig.weightKg }}kg · {{ pig.penCode || '无栏位' }} · {{ pig.batchNo || '未入批' }}
              <template v-if="pig.withdrawal?.activeMedicationCount">
                · {{ pig.withdrawal.restricted ? '休药中' : '休药已满' }}：{{ pig.withdrawal.latestMedicineName }}
              </template>
            </div>
          </div>
          <span class="tag" :style="{ background: pig.breedColor, color: '#fff' }">{{ pig.breedName }}</span>
          <span class="tag">{{ pig.statusName }}</span>
          <span v-if="pig.withdrawal?.restricted" class="tag solid" style="background: var(--hog-red)">
            休药至 {{ pig.withdrawal.deadline }}
          </span>
          <span v-else-if="pig.withdrawal?.deadline" class="tag" style="border-color: var(--hog-green); color: var(--hog-green)">
            {{ pig.withdrawal.earliestShipDate }} 可出栏
          </span>
          <el-dropdown trigger="click" @command="(c) => c === 'edit' ? openEdit(pig) : c === 'del' ? removePig(pig) : c === 'immune' ? openImmune(pig) : changeOne(pig, c)">
            <el-button size="small" link>操作</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="TRANSFERRED">标记转栏</el-dropdown-item>
                <el-dropdown-item command="SOLD">标记出栏</el-dropdown-item>
                <el-dropdown-item command="DEAD">标记死亡</el-dropdown-item>
                <el-dropdown-item command="CULLED">标记淘汰</el-dropdown-item>
                <el-dropdown-item command="immune" divided>免疫计划</el-dropdown-item>
                <el-dropdown-item command="edit">编辑</el-dropdown-item>
                <el-dropdown-item command="del">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div v-if="!filtered.length" class="empty-tip">没有符合条件的猪只</div>
      </div>

      <div class="panel">
        <div class="section-title">品种 / 状态速览</div>
        <div class="mini-card" style="margin-bottom: 10px">
          <div class="k">在栏猪只</div>
          <div class="v">{{ pigs.filter((p) => p.status === 'ON_FARM' || p.status === 'TRANSFERRED').length }}</div>
        </div>
        <div v-for="b in BREEDS" :key="b.value" style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
          <span class="tag solid" :style="{ background: { YORKSHIRE: '#ffb74d', LANDRACE: '#4fc3f7', DUROC: '#e57373', PIETRAIN: '#81c784', BERKSHIRE: '#ba68c8' }[b.value] }">{{ b.label }}</span>
          <div class="load-bar" style="flex: 1 1 auto; margin: 0">
            <i :style="{ width: Math.round((pigs.filter((p) => p.breed === b.value).length * 100) / (pigs.length || 1)) + '%' }"></i>
          </div>
          <b style="font-size: 12px">{{ pigs.filter((p) => p.breed === b.value).length }}</b>
        </div>
      </div>
    </div>

    <!-- 侧滑批量操作 -->
    <el-drawer v-model="batchOpen" title="批量操作" direction="rtl" size="380px" @close="selected = []">
      <div class="batch-drawer">
        <div class="mini-card" style="margin-bottom: 12px">
          <div class="k">已勾选猪只</div>
          <div class="v">{{ selected.length }} 头</div>
        </div>
        <div class="pig-row" v-for="pig in selectedPigs" :key="pig.id">
          <div>
            <div class="pig-ear">{{ pig.earTag }}</div>
            <div class="pig-sub">{{ pig.breedName }} · {{ pig.statusName }} · {{ pig.penCode || '无栏位' }}</div>
          </div>
        </div>
        <div v-if="!selectedPigs.length" class="empty-tip">还没有勾选猪只</div>

        <div class="section-title" style="margin-top: 14px">批量推进状态</div>
        <el-select v-model="batchStatus" size="small" style="width: 100%; margin-bottom: 8px">
          <el-option label="标记转栏" value="TRANSFERRED" />
          <el-option label="标记出栏" value="SOLD" />
          <el-option label="标记死亡" value="DEAD" />
          <el-option label="标记淘汰" value="CULLED" />
        </el-select>
        <el-button type="primary" size="small" style="width: 100%" @click="runBatchStatus">执行批量状态变更</el-button>

        <div class="section-title" style="margin-top: 16px">批量转栏</div>
        <el-select v-model="batchPenId" size="small" placeholder="选择目标栏位（受限栏位不可选）" style="width: 100%; margin-bottom: 8px">
          <el-option
            v-for="pen in pens"
            :key="pen.id"
            :label="penLabel(pen)"
            :value="pen.id"
            :disabled="!!penBlockedReason(pen)"
          />
        </el-select>
        <el-button size="small" style="width: 100%" @click="runBatchTransfer">批量搬移到该栏位</el-button>
        <el-button size="small" style="width: 100%; margin-top: 8px" @click="closeBatch">清空选择</el-button>

        <div class="section-title" style="margin-top: 16px">批量用药登记</div>
        <div class="mini-card" style="margin-bottom: 8px">
          <div class="k">休药截止日</div>
          <div class="v" style="font-size: 13px">按所选猪只仍有效用药记录的最晚日期动态重算</div>
        </div>
        <el-button type="warning" size="small" style="width: 100%" @click="openMedication">登记药品 / 休药天数 / 经办人</el-button>
      </div>
    </el-drawer>

    <!-- 新建 / 编辑猪只 -->
    <el-dialog v-model="dialog" :title="editingId ? '编辑猪只' : '新建猪只'" width="440px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="耳标号"><el-input v-model="form.earTag" placeholder="如 ET-2609-0101" /></el-form-item>
        <el-form-item label="品种">
          <el-select v-model="form.breed" style="width: 100%">
            <el-option v-for="b in BREEDS" :key="b.value" :label="b.label" :value="b.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.gender" style="width: 100%">
            <el-option label="母" value="FEMALE" />
            <el-option label="公" value="MALE" />
          </el-select>
        </el-form-item>
        <el-form-item label="出生日期">
          <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="不能晚于今天" style="width: 100%" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="form.sourceType" style="width: 100%">
            <el-option label="自繁" value="SELF" />
            <el-option label="外购" value="PURCHASE" />
          </el-select>
        </el-form-item>
        <el-form-item label="所在栏位">
          <el-select v-model="form.penId" clearable style="width: 100%" placeholder="检修 / 待清空 / 停用栋舍的栏位不可入栏">
            <el-option
              v-for="pen in pens"
              :key="pen.id"
              :label="penLabel(pen)"
              :value="pen.id"
              :disabled="!!penBlockedReason(pen)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="体重 kg"><el-input-number v-model="form.weightKg" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
    <!-- 批量用药 -->
    <el-dialog v-model="medDialog" :title="`批量用药登记（${selected.length} 头）`" width="460px">
      <el-alert
        title="已出栏、死亡或淘汰的猪只会被后端拒绝；保存后猪只、批次和溯源时间线同步重算。"
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
        <el-form-item label="休药天数" required>
          <el-input-number v-model="medForm.withdrawalDays" :min="0" :max="365" />
          <span style="margin-left: 8px; color: var(--ink-dim); font-size: 12px">截止日次日才可出栏</span>
        </el-form-item>
        <el-form-item label="经办人" required><el-input v-model="medForm.operator" placeholder="如 赵德明" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="medForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="medDialog = false">取消</el-button>
        <el-button type="primary" :loading="medSaving" @click="submitMedication">保存用药登记</el-button>
      </template>
    </el-dialog>
    <!-- 猪只免疫计划追溯 -->
    <el-dialog v-model="immuneDialog" :title="`免疫计划追溯 · ${immunePig?.earTag || ''}`" width="760px">
      <el-table :data="immuneTasks" size="small" max-height="420" v-loading="!immuneTasks.length">
        <el-table-column prop="planCode" label="计划" width="90" />
        <el-table-column prop="vaccineName" label="疫苗" width="150" />
        <el-table-column label="剂次" width="90">
          <template #default="{ row }">第 {{ row.doseNo }}/{{ row.totalDoses }} 剂</template>
        </el-table-column>
        <el-table-column prop="plannedDate" label="计划日期" width="100" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small"
                    :type="{ PENDING: 'warning', MISSED: 'danger', DONE: 'success', MADE_UP: 'success', TERMINATED: 'info' }[row.status]">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="penCode" label="栏位" width="110" />
        <el-table-column label="明细" min-width="220">
          <template #default="{ row }">
            <div style="font-size: 12px">
              <template v-if="row.status === 'MADE_UP'">补种 {{ row.makeUpDate }}（{{ row.makeUpReason }}），逾期 {{ row.overdueDays }} 天</template>
              <template v-else-if="row.status === 'DONE'">完成于 {{ row.completedDate }}</template>
              <template v-else-if="row.status === 'TERMINATED'">已终止：{{ row.terminateReasonName }}</template>
              <template v-else-if="row.overdueDays > 0">已逾期 {{ row.overdueDays }} 天（宽限 {{ row.graceDays }} 天）</template>
              <template v-else>宽限 {{ row.graceDays }} 天</template>
            </div>
            <div style="font-size: 12px; color: var(--ink-dim)">依据：{{ row.basis }}</div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!immuneTasks.length" class="empty-tip">该猪只暂无免疫计划任务，可到「防疫」页生成</div>
      <template #footer>
        <el-button type="primary" @click="immuneDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
