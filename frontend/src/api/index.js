import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const house = {
  list: () => call(http.get('/house/list')),
  grid: () => call(http.get('/house/grid')),
  pens: (houseId) => call(http.get('/house/pens', { params: { houseId } })),
  pen: (penId) => call(http.get(`/house/pen/${penId}`)),
  penPigs: (penId) => call(http.get(`/house/pen/${penId}/pigs`)),
  get: (id) => call(http.get(`/house/${id}`)),
  create: (data) => call(http.post('/house', data)),
  update: (id, data) => call(http.put(`/house/${id}`, data)),
  /** 状态切换预检：受影响栏位、猪只清单、阻塞原因 */
  statusReview: (id, targetStatus) =>
    call(http.get(`/house/${id}/status/review`, { params: { targetStatus } })),
  /** 操作员确认后的状态切换（检修 / 待清空 / 停用 / 恢复在用） */
  status: (id, data) => call(http.post(`/house/${id}/status`, data)),
  statusLogs: (houseId) => call(http.get('/house/status-logs', { params: { houseId } })),
  remove: (id) => call(http.delete(`/house/${id}`)),
  createPen: (data) => call(http.post('/house/pen', data)),
  updatePen: (id, data) => call(http.put(`/house/pen/${id}`, data)),
  removePen: (id) => call(http.delete(`/house/pen/${id}`))
}

const pig = {
  list: (params) => call(http.get('/pig/list', { params })),
  options: () => call(http.get('/pig/options')),
  get: (id) => call(http.get(`/pig/${id}`)),
  create: (data) => call(http.post('/pig', data)),
  update: (id, data) => call(http.put(`/pig/${id}`, data)),
  status: (id, status) => call(http.post(`/pig/${id}/status?status=${encodeURIComponent(status)}`)),
  batchStatus: (ids, status) => call(http.post('/pig/batch/status', { ids, status })),
  remove: (id) => call(http.delete(`/pig/${id}`))
}

const batch = {
  list: () => call(http.get('/batch/list')),
  get: (id) => call(http.get(`/batch/${id}`)),
  create: (data) => call(http.post('/batch', data)),
  update: (id, data) => call(http.put(`/batch/${id}`, data)),
  advance: (id) => call(http.post(`/batch/${id}/advance`)),
  ship: (id, count) => call(http.post(`/batch/${id}/ship?count=${count}`)),
  shipmentPreview: (id, data) => call(http.post(`/batch/${id}/shipment/preview`, data)),
  shipmentConfirm: (id, data) => call(http.post(`/batch/${id}/shipment/confirm`, data)),
  remove: (id) => call(http.delete(`/batch/${id}`))
}

const transfer = {
  list: () => call(http.get('/transfer/list')),
  create: (data) => call(http.post('/transfer', data)),
  pens: (houseId) => call(http.get('/transfer/pens', { params: { houseId } })),
  houses: () => call(http.get('/transfer/houses')),
  pigsInPen: (penId) => call(http.get(`/transfer/pen/${penId}/pigs`)),
  feedings: () => call(http.get('/transfer/feeding')),
  createFeeding: (data) => call(http.post('/transfer/feeding', data)),
  feedBatches: () => call(http.get('/transfer/feed-batch')),
  createFeedBatch: (data) => call(http.post('/transfer/feed-batch', data))
}

const medication = {
  list: (pigId) => call(http.get('/medication/list', { params: { pigId } })),
  create: (data) => call(http.post('/medication', data)),
  update: (id, data) => call(http.put(`/medication/${id}`, data)),
  revoke: (id, data) => call(http.post(`/medication/${id}/revoke`, data))
}

const trace = {
  vaccines: () => call(http.get('/trace/vaccine')),
  createVaccine: (data) => call(http.post('/trace/vaccine', data)),
  list: (traceType) => call(http.get('/trace/list', { params: { traceType } })),
  create: (data) => call(http.post('/trace', data)),
  attach: (id, ref) => call(http.post(`/trace/${id}/target`, ref)),
  targetOptions: (targetType) => call(http.get('/trace/target-options', { params: { targetType } }))
}

const immune = {
  plans: () => call(http.get('/immune/plan/list')),
  createPlan: (data) => call(http.post('/immune/plan', data)),
  planStatus: (id, status) => call(http.post(`/immune/plan/${id}/status?status=${encodeURIComponent(status)}`)),
  /** 生成预检：逐头解释 纳入 / 暂缓 / 不再适用 的原因，不落库 */
  preview: (id) => call(http.get(`/immune/plan/${id}/preview`)),
  /** 生成到期接种任务（生成时名单快照） */
  generate: (id) => call(http.post(`/immune/plan/${id}/generate`)),
  /** 看板：待接种 / 临期 / 漏种 / 已补种 / 已完成 / 已终止 分组 */
  board: () => call(http.get('/immune/board')),
  /** 猪只详情追溯：该猪只的全部接种任务 */
  tasks: (pigId) => call(http.get('/immune/task/list', { params: { pigId } })),
  /** 批量确认接种：一次多头，局部失败可核对，requestNo 幂等 */
  confirm: (data) => call(http.post('/immune/task/confirm', data))
}

const stats = {
  overview: () => call(http.get('/stats/overview')),
  houseType: () => call(http.get('/stats/house-type')),
  breed: () => call(http.get('/stats/breed')),
  batchStatus: () => call(http.get('/stats/batch-status')),
  dict: () => call(http.get('/stats/dict'))
}

export default { http, house, pig, batch, medication, transfer, trace, immune, stats }
