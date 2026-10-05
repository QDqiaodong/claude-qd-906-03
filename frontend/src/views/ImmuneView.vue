<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const STAGES = [
  { value: 'ALL', label: '全阶段' },
  { value: 'SUCKLING', label: '哺乳仔猪' },
  { value: 'NURSERY', label: '保育猪' },
  { value: 'GROWER', label: '育成猪' },
  { value: 'FINISHING', label: '育肥猪' },
  { value: 'BREEDING', label: '种用猪' }
]
const VACCINES = [
  '猪瘟活疫苗', '口蹄疫 O 型灭活疫苗', '伪狂犬病活疫苗',
  '圆环病毒灭活疫苗', '高致病性蓝耳病活疫苗', '细小病毒灭活疫苗',
  '猪传染性胃肠炎二联苗', '支原体灭活疫苗'
]
// 看板分组展示色
const GROUP_STYLE = {
  DUE: { color: '#2f80c2' },
  DUE_SOON: { color: '#ff8f00' },
  MISSED: { color: '#e2542c' },
  MADE_UP: { color: '#5aa832' },
  DONE: { color: '#7cb305' },
  TERMINATED: { color: '#9e9e9e' }
}
const DECISION_TAG = {
  INCLUDED: 'success',
  DEFERRED: 'warning',
  NOT_APPLICABLE: 'info'
}
const RESULT_TAG = {
  DONE: 'success',
  MADE_UP: 'success',
  ALREADY: 'info',
  TERMINATED: 'warning',
  FAILED: 'danger'
}

const plans = ref([])
const board = ref(null)
const planFilter = ref('')
const selected = ref([])

const planDialog = ref(false)
const planForm = ref(emptyPlan())
const previewDialog = ref(false)
const preview = ref(null)
const confirmDialog = ref(false)
const confirmForm = ref(emptyConfirm())
const confirmResult = ref(null)
const saving = ref(false)

function emptyPlan () {
  return {
    planCode: '', vaccineName: '猪瘟活疫苗', stage: 'ALL',
    firstDoseAgeDays: 21, doseIntervalDays: 180, totalDoses: 2,
    graceDays: 7, doseMl: 2, note: ''
  }
}

function emptyConfirm () {
  return {
    requestNo: makeRequestNo(),
    confirmDate: new Date().toISOString().slice(0, 10),
    doseMl: null,
    operator: '周防疫员',
    makeUpDate: new Date().toISOString().slice(0, 10),
    makeUpReason: ''
  }
}

function makeRequestNo () {
  if (window.crypto?.randomUUID) return `IMM-${crypto.randomUUID().replaceAll('-', '').slice(0, 24).toUpperCase()}`
  return `IMM-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`.toUpperCase()
}

async function load () {
  plans.value = await api.immune.plans()
  board.value = await api.immune.board()
}

const groups = computed(() => {
  if (!board.value) return []
  return board.value.groups.map((g) => ({
    ...g,
    tasks: planFilter.value ? g.tasks.filter((t) => t.planCode === planFilter.value) : g.tasks
  }))
})

// 可勾选的任务：待接种 / 临期 / 漏种（即未完成、未终止）
function confirmable (task) {
  return task.status === 'PENDING' || task.status === 'MISSED'
}

const selectedTasks = computed(() => {
  if (!board.value) return []
  const all = board.value.groups.flatMap((g) => g.tasks)
  return all.filter((t) => selected.value.includes(t.id))
})
const hasMissedSelected = computed(() => selectedTasks.value.some((t) => t.status === 'MISSED'))

function toggle (task) {
  if (!confirmable(task)) return
  const idx = selected.value.indexOf(task.id)
  if (idx >= 0) selected.value.splice(idx, 1)
  else selected.value.push(task.id)
}

async function submitPlan () {
  try {
    await api.immune.createPlan(planForm.value)
    ElMessage.success('免疫计划已创建，可执行「生成预检」查看逐头纳入情况')
    planDialog.value = false
    planForm.value = emptyPlan()
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function runPreview (plan, thenGenerate) {
  try {
    preview.value = thenGenerate ? await api.immune.generate(plan.id) : await api.immune.preview(plan.id)
    previewDialog.value = true
    if (thenGenerate) load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function togglePlan (plan) {
  const target = plan.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (target === 'DISABLED') {
    try {
      await ElMessageBox.confirm(
        `停用后不再生成新任务，未完成的 ${plan.openTaskCount} 条任务将进入「已终止」（原因：计划已停用，可追溯）。确认停用 ${plan.planCode}？`,
        '停用免疫计划',
        { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
      )
    } catch { return }
  }
  try {
    await api.immune.planStatus(plan.id, target)
    ElMessage.success(target === 'DISABLED' ? '计划已停用' : '计划已启用')
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openConfirm () {
  if (!selected.value.length) {
    ElMessage.warning('请先在待接种 / 临期 / 漏种分组勾选任务')
    return
  }
  confirmForm.value = emptyConfirm()
  confirmResult.value = null
  confirmDialog.value = true
}

async function submitConfirm () {
  if (hasMissedSelected.value && (!confirmForm.value.makeUpDate || !confirmForm.value.makeUpReason.trim())) {
    ElMessage.warning('选中任务包含漏种，必须登记补种日期与原因后才能完成')
    return
  }
  saving.value = true
  try {
    const result = await api.immune.confirm({ ...confirmForm.value, taskIds: selected.value })
    confirmResult.value = result
    if (result.replay) {
      ElMessage.info('该确认批次已处理过，以下为原处理结果（未产生重复免疫记录）')
    } else if (result.failedCount > 0) {
      ElMessage.warning(`局部完成：成功 ${result.doneCount + result.madeUpCount} 条，失败 ${result.failedCount} 条，可逐条核对`)
    } else {
      ElMessage.success(`确认完成：接种 ${result.doneCount} 条、补种 ${result.madeUpCount} 条`)
    }
    load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

function closeConfirm () {
  confirmDialog.value = false
  selected.value = []
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">免疫计划与接种任务</span>
      <span class="view-tip">计划规则 → 到期任务 → 批量确认 / 漏种补种，全链路可追溯</span>
    </div>

    <el-alert
      v-if="board"
      :title="board.policyNote"
      type="warning"
      :closable="false"
      style="margin-bottom: 12px"
    />

    <div class="grid-2" style="grid-template-columns: minmax(0, 1fr) minmax(0, 1.5fr)">
      <!-- 免疫计划规则 -->
      <div class="panel">
        <div class="section-title">
          免疫计划规则（{{ plans.length }}）
          <el-button size="small" type="primary" style="margin-left: auto" @click="planDialog = true">新建计划</el-button>
        </div>
        <div v-for="plan in plans" :key="plan.id" class="pig-row" style="align-items: flex-start">
          <div style="flex: 1 1 auto; min-width: 0">
            <div class="pig-ear">
              {{ plan.planCode }} · {{ plan.vaccineName }}
              <el-tag size="small" :type="plan.status === 'ACTIVE' ? 'success' : 'info'" style="margin-left: 6px">
                {{ plan.statusName }}
              </el-tag>
              <el-tag v-if="plan.missedTaskCount" size="small" type="danger" style="margin-left: 4px">
                漏种 {{ plan.missedTaskCount }}
              </el-tag>
            </div>
            <div class="pig-sub">
              适用 {{ plan.stageName }} · 首剂 {{ plan.firstDoseAgeDays }} 日龄 ·
              间隔 {{ plan.doseIntervalDays }} 天 · 共 {{ plan.totalDoses }} 剂 ·
              宽限 {{ plan.graceDays }} 天 · {{ plan.doseMl }}ml · 未完成 {{ plan.openTaskCount }}
            </div>
            <div class="pig-sub" v-if="plan.note">{{ plan.note }}</div>
          </div>
          <div style="display: flex; flex-direction: column; gap: 4px">
            <el-button size="small" link type="primary" @click="runPreview(plan, false)">生成预检</el-button>
            <el-button size="small" link type="primary" :disabled="plan.status !== 'ACTIVE'" @click="runPreview(plan, true)">生成任务</el-button>
            <el-button size="small" link :type="plan.status === 'ACTIVE' ? 'danger' : 'success'" @click="togglePlan(plan)">
              {{ plan.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
          </div>
        </div>
        <div v-if="!plans.length" class="empty-tip">还没有免疫计划，点击「新建计划」定义规则</div>
      </div>

      <!-- 接种任务看板 -->
      <div class="panel">
        <div class="section-title">
          接种任务看板（{{ board?.total || 0 }}）
          <el-button size="small" type="primary" style="margin-left: auto" @click="openConfirm">
            批量确认接种（已选 {{ selected.length }}）
          </el-button>
        </div>
        <div class="chip-bar" style="margin-bottom: 8px">
          <span class="chip" :class="{ on: planFilter === '' }" @click="planFilter = ''">全部计划</span>
          <span v-for="p in plans" :key="p.id" class="chip" :class="{ on: planFilter === p.planCode }" @click="planFilter = p.planCode">
            {{ p.planCode }}
          </span>
        </div>

        <div v-for="group in groups" :key="group.key" style="margin-bottom: 14px">
          <div class="section-title" :style="{ color: GROUP_STYLE[group.key]?.color }">
            {{ group.name }}（{{ group.tasks.length }}）
          </div>
          <div v-for="task in group.tasks" :key="task.id" class="pig-row"
               :class="{ on: selected.includes(task.id) }"
               :style="confirmable(task) ? '' : 'opacity: 0.85'">
            <el-checkbox v-if="confirmable(task)" :model-value="selected.includes(task.id)" @click.stop="toggle(task)" />
            <div style="flex: 1 1 auto; min-width: 0" @click="toggle(task)">
              <div class="pig-ear">
                {{ task.earTag }} · {{ task.vaccineName }}
                <span class="tag solid" :style="{ background: GROUP_STYLE[group.key]?.color }">
                  第 {{ task.doseNo }}/{{ task.totalDoses }} 剂
                </span>
                <span v-if="task.overdueDays > 0 && confirmable(task)" class="tag solid" style="background: var(--hog-red)">
                  逾期 {{ task.overdueDays }} 天
                </span>
              </div>
              <div class="pig-sub">
                计划日期 <b>{{ task.plannedDate }}</b>（宽限 {{ task.graceDays }} 天）· 栏位 {{ task.penCode }}
                <template v-if="task.status === 'MADE_UP'">
                  · 补种于 {{ task.makeUpDate }}（{{ task.makeUpReason }}），逾期 {{ task.overdueDays }} 天 · 免疫记录 #{{ task.vaccineRecordId }}
                </template>
                <template v-else-if="task.status === 'DONE'">
                  · 完成于 {{ task.completedDate }} · 免疫记录 #{{ task.vaccineRecordId }}
                </template>
                <template v-else-if="task.status === 'TERMINATED'">
                  · 已终止：{{ task.terminateReasonName }}
                  <template v-if="task.vaccineRecordId">（等效免疫记录 #{{ task.vaccineRecordId }}）</template>
                </template>
              </div>
              <div class="pig-sub" style="color: var(--ink-dim)">依据：{{ task.basis }}</div>
            </div>
          </div>
          <div v-if="!group.tasks.length" class="empty-tip" style="padding: 8px">暂无任务</div>
        </div>
      </div>
    </div>

    <!-- 新建计划 -->
    <el-dialog v-model="planDialog" title="新建免疫计划" width="480px">
      <el-form :model="planForm" label-width="100px">
        <el-form-item label="疫苗">
          <el-select v-model="planForm.vaccineName" style="width: 100%">
            <el-option v-for="v in VACCINES" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用阶段">
          <el-select v-model="planForm.stage" style="width: 100%">
            <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="首剂日龄">
          <el-input-number v-model="planForm.firstDoseAgeDays" :min="0" :max="365" />
          <span style="margin-left: 8px; color: var(--ink-dim); font-size: 12px">第 1 剂 = 出生日期 + 首剂日龄</span>
        </el-form-item>
        <el-form-item label="剂次间隔">
          <el-input-number v-model="planForm.doseIntervalDays" :min="1" :max="365" />
          <span style="margin-left: 8px; color: var(--ink-dim); font-size: 12px">第 N 剂 = 第 1 剂 + (N-1) × 间隔</span>
        </el-form-item>
        <el-form-item label="总剂次"><el-input-number v-model="planForm.totalDoses" :min="1" :max="10" /></el-form-item>
        <el-form-item label="宽限天数">
          <el-input-number v-model="planForm.graceDays" :min="0" :max="60" />
          <span style="margin-left: 8px; color: var(--ink-dim); font-size: 12px">超过即进入漏种</span>
        </el-form-item>
        <el-form-item label="默认剂量 ml"><el-input-number v-model="planForm.doseMl" :min="0.1" :max="20" :precision="2" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="planForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" @click="submitPlan">保存</el-button>
      </template>
    </el-dialog>

    <!-- 生成预检 / 生成结果：逐头解释 纳入 / 暂缓 / 不再适用 -->
    <el-dialog v-model="previewDialog" width="720px"
               :title="preview?.generated ? `生成完成：新增 ${preview?.createdTaskCount} 条任务` : '生成预检（不落库）'">
      <div v-if="preview" style="margin-bottom: 10px">
        <el-tag type="success">纳入 {{ preview.includedCount }}</el-tag>
        <el-tag type="warning" style="margin-left: 6px">暂缓 {{ preview.deferredCount }}</el-tag>
        <el-tag type="info" style="margin-left: 6px">不再适用 {{ preview.notApplicableCount }}</el-tag>
        <span style="margin-left: 10px; font-size: 12px; color: var(--ink-dim)">
          执行口径：生成时名单快照；只生成今天 + 30 天窗口内的剂次，已存在的剂次不重复生成
        </span>
      </div>
      <el-table :data="preview?.items || []" size="small" max-height="420">
        <el-table-column prop="earTag" label="耳标号" width="130" />
        <el-table-column label="决定" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="DECISION_TAG[row.decision]">{{ row.decisionName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划剂次" width="210">
          <template #default="{ row }">
            <span v-for="d in row.doses" :key="d.doseNo" class="tag" style="margin-right: 4px">
              第 {{ d.doseNo }} 剂 {{ d.plannedDate }}<template v-if="d.existed">（已存在）</template>
            </span>
            <span v-if="!row.doses?.length">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" />
      </el-table>
      <template #footer>
        <el-button type="primary" @click="previewDialog = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 批量确认接种 -->
    <el-dialog v-model="confirmDialog" :title="`批量确认接种（${selectedTasks.length} 条任务）`" width="760px"
               @close="selected = []">
      <template v-if="!confirmResult">
        <el-alert
          v-if="hasMissedSelected"
          title="选中任务包含漏种：必须登记补种日期与原因后才能完成；原计划日期与逾期天数会保留。"
          type="error"
          :closable="false"
          style="margin-bottom: 10px"
        />
        <el-table :data="selectedTasks" size="small" max-height="240" style="margin-bottom: 12px">
          <el-table-column prop="earTag" label="耳标号" width="130" />
          <el-table-column prop="vaccineName" label="疫苗" />
          <el-table-column label="剂次" width="80">
            <template #default="{ row }">第 {{ row.doseNo }}/{{ row.totalDoses }} 剂</template>
          </el-table-column>
          <el-table-column prop="plannedDate" label="计划日期" width="105" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'MISSED' ? 'danger' : 'warning'">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="penCode" label="栏位" width="110" />
        </el-table>
        <el-form :model="confirmForm" label-width="100px">
          <el-form-item label="接种日期">
            <el-date-picker v-model="confirmForm.confirmDate" type="date" value-format="YYYY-MM-DD"
                            :disabled-date="(d) => d.getTime() > Date.now()" style="width: 100%" />
          </el-form-item>
          <el-form-item label="剂量 ml">
            <el-input-number v-model="confirmForm.doseMl" :min="0.1" :max="20" :precision="2" placeholder="留空用计划默认剂量" />
            <span style="margin-left: 8px; color: var(--ink-dim); font-size: 12px">留空则按计划默认剂量</span>
          </el-form-item>
          <el-form-item label="免疫员"><el-input v-model="confirmForm.operator" /></el-form-item>
          <template v-if="hasMissedSelected">
            <el-form-item label="补种日期" required>
              <el-date-picker v-model="confirmForm.makeUpDate" type="date" value-format="YYYY-MM-DD"
                              :disabled-date="(d) => d.getTime() > Date.now()" style="width: 100%" />
            </el-form-item>
            <el-form-item label="补种原因" required>
              <el-input v-model="confirmForm.makeUpReason" placeholder="如 仔猪腹泻痊愈后补种 / 栏舍消毒延迟" />
            </el-form-item>
          </template>
        </el-form>
        <div style="font-size: 12px; color: var(--ink-dim)">
          确认批次号 {{ confirmForm.requestNo }}：同一批次重复提交不会产生重复免疫记录；每头猪、同一计划和剂次只形成一个完成结果。
        </div>
      </template>

      <!-- 逐条结果：局部失败时其余结果与任务状态保持可核对 -->
      <template v-else>
        <div style="margin-bottom: 10px">
          <el-tag type="success">已完成 {{ confirmResult.doneCount }}</el-tag>
          <el-tag type="success" style="margin-left: 6px">已补种 {{ confirmResult.madeUpCount }}</el-tag>
          <el-tag type="warning" style="margin-left: 6px">已终止 {{ confirmResult.terminatedCount }}</el-tag>
          <el-tag type="info" style="margin-left: 6px">已处理 {{ confirmResult.alreadyCount }}</el-tag>
          <el-tag type="danger" style="margin-left: 6px">失败 {{ confirmResult.failedCount }}</el-tag>
          <el-tag v-if="confirmResult.replay" type="info" style="margin-left: 6px">幂等回放</el-tag>
        </div>
        <el-table :data="confirmResult.results" size="small" max-height="380">
          <el-table-column prop="earTag" label="耳标号" width="125" />
          <el-table-column prop="planCode" label="计划" width="90" />
          <el-table-column label="剂次" width="60">
            <template #default="{ row }">第 {{ row.doseNo }} 剂</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="RESULT_TAG[row.result]">{{ row.resultName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="plannedDate" label="计划日期" width="100" />
          <el-table-column label="逾期" width="60">
            <template #default="{ row }">{{ row.overdueDays }} 天</template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
        </el-table>
      </template>

      <template #footer>
        <template v-if="!confirmResult">
          <el-button @click="confirmDialog = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submitConfirm">确认接种</el-button>
        </template>
        <el-button v-else type="primary" @click="closeConfirm">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>
