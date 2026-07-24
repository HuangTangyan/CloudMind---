<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  AlertTriangle,
  ArrowLeft,
  Bot,
  ClipboardCheck,
  Cloud,
  FilePlus2,
  FileText,
  Folder,
  FolderPlus,
  HardDrive,
  Home,
  Images,
  LayoutGrid,
  List,
  LoaderCircle,
  LogOut,
  Menu,
  Search,
  ShieldCheck,
  Sparkles,
  Trash2,
  Upload,
  Users,
  Video,
  WandSparkles,
  X,
} from '@lucide/vue'
import AppBrand from './components/AppBrand.vue'
import FileTypeIcon from './components/FileTypeIcon.vue'

const API_BASE = '/api'
const ALLOWED_UPLOAD_ACCEPT = [
  '.pdf', '.txt', '.md', '.csv', '.json', '.xml', '.yml', '.yaml',
  '.java', '.py', '.js', '.ts', '.css', '.sql', '.properties',
  '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.png', '.jpg', '.jpeg', '.gif', '.webp',
  '.mp4', '.webm', '.mov', '.mp3', '.wav', '.ogg'
].join(',')
const previousToken = localStorage.getItem('cloudmind-token') || ''
const previousUser = localStorage.getItem('cloudmind-user') || 'null'
const token = ref(sessionStorage.getItem('cloudmind-token') || previousToken)
const refreshToken = ref(sessionStorage.getItem('cloudmind-refresh-token') || '')
const user = ref(JSON.parse(sessionStorage.getItem('cloudmind-user') || previousUser))
localStorage.removeItem('cloudmind-token')
localStorage.removeItem('cloudmind-user')
if (token.value) sessionStorage.setItem('cloudmind-token', token.value)
if (user.value) sessionStorage.setItem('cloudmind-user', JSON.stringify(user.value))
const username = ref('')
const password = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const message = ref('')
const loading = ref(false)
const sidebarOpen = ref(false)
const items = ref([])
const usedBytes = ref(0)
const quotaBytes = ref(0)
const remainingBytes = ref(0)
const elasticExtraBytes = ref(0)
const breadcrumb = ref([{ id: null, name: '我的文件' }])
const searchKeyword = ref('')
const viewMode = ref('files')
const fileInput = ref(null)
const folderInput = ref(null)
const sessionSecurityButton = ref(null)
const sessionCloseButton = ref(null)
const fileDisplayMode = ref(localStorage.getItem('cloudmind-file-display') || 'list')
const dragActive = ref(false)
const dragDepth = ref(0)
const selectedIds = ref([])
const focusFile = ref(null)
const sideRelatedItems = ref([])
const sideRecommendLoading = ref(false)
const collapsedRecommendGroups = ref([])

const previewVisible = ref(false)
const previewData = ref(null)
const relatedItems = ref([])
const versions = ref([])
const mediaRef = ref(null)
const playbackRate = ref(1)
const activePreviewObjectUrl = ref('')
const thumbnailUrls = ref({})
let thumbnailGeneration = 0
let refreshSessionPromise = null
const targetDialog = ref({ visible: false, type: 'move', item: null, targetParentId: null, folders: [] })
const sessionDialog = ref({ visible: false, loading: false, error: '', items: [] })

const adminTab = ref('dashboard')
const adminUsers = ref([])
const storageOverview = ref(null)
const adminCreateForm = ref({ username: '', password: '', role: 'USER', quotaGb: 10 })
const auditUsers = ref([])
const auditItems = ref([])
const auditBreadcrumb = ref([])
const currentAuditUser = ref(null)
const auditUserKeyword = ref('')
const adminPreviewMode = ref(false)
const aiConfig = ref({ enabled: false, provider: 'DeepSeek / OpenAI 兼容', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', apiKey: '', hasApiKey: false, reviewPrompt: '' })
const aiTestMessage = ref('')
const knowledgeQuestion = ref('')
const knowledgeMessages = ref([])
const knowledgeLoading = ref(false)
const knowledgeScopeType = ref('ALL')
const knowledgeScopeId = ref(null)
const knowledgeScopeIds = ref([])
const knowledgeSources = ref({ files: [], folders: [], fileCount: 0, folderCount: 0, knowledgeFileCount: 0 })
const knowledgeSourceKeyword = ref('')
const knowledgeSourcesLoading = ref(false)
const knowledgeOverviewLoading = ref(false)
const knowledgeScrollRef = ref(null)
const adminEditDialog = ref({ visible: false, user: null, role: 'USER', quotaGb: 10 })

const currentParentId = computed(() => breadcrumb.value[breadcrumb.value.length - 1]?.id ?? null)
const isAdmin = computed(() => user.value?.role === 'ADMIN')
const mustChangePassword = computed(() => Boolean(user.value?.mustChangePassword))
const canDragUpload = computed(() => Boolean(user.value) && !['admin', 'trash', 'gallery', 'knowledge'].includes(viewMode.value))
const loginMessageIsBanned = computed(() => String(message.value || '').includes('禁用') || String(message.value || '').includes('封禁'))
const filteredAuditUsers = computed(() => {
  const keyword = auditUserKeyword.value.trim().toLowerCase()
  if (!keyword) return auditUsers.value
  return auditUsers.value.filter(u => String(u.username || '').toLowerCase().includes(keyword))
})
const selectedItems = computed(() => items.value.filter(item => selectedIds.value.includes(item.id)))
const recommendedGroups = computed(() => buildRecommendGroups(focusFile.value, sideRelatedItems.value))
const usedPercent = computed(() => percentage(usedBytes.value, quotaBytes.value))
const remainPercent = computed(() => Math.max(0, 100 - usedPercent.value))
const selectedKnowledgeFiles = computed(() => {
  const ids = new Set(knowledgeScopeIds.value)
  return knowledgeSources.value.files.filter(item => ids.has(item.id))
})
const selectedKnowledgeSource = computed(() => {
  if (knowledgeScopeType.value === 'FILE') {
    return selectedKnowledgeFiles.value[0] || null
  }
  if (knowledgeScopeType.value === 'FOLDER') {
    return knowledgeSources.value.folders.find(item => item.id === knowledgeScopeId.value) || null
  }
  return null
})
const knowledgeScopeLabel = computed(() => {
  if (knowledgeScopeType.value === 'ALL') return `全部文件（${knowledgeSources.value.knowledgeFileCount || knowledgeSources.value.fileCount || 0} 个可用资料）`
  if (knowledgeScopeType.value === 'FILE') {
    const files = selectedKnowledgeFiles.value
    if (!files.length) return '还未选择文件'
    if (files.length === 1) return files[0].path || files[0].name
    const previewNames = files.slice(0, 2).map(item => item.name).join('、')
    return `已选择 ${files.length} 个文件：${previewNames}${files.length > 2 ? ' 等' : ''}`
  }
  const source = selectedKnowledgeSource.value
  if (!source) return '还未选择文件夹'
  return `${source.path || source.name}（${source.childKnowledgeCount || 0} 个可用资料）`
})
const filteredKnowledgeSources = computed(() => {
  const keyword = knowledgeSourceKeyword.value.trim().toLowerCase()
  const pool = knowledgeScopeType.value === 'FOLDER' ? knowledgeSources.value.folders : knowledgeSources.value.files
  if (!keyword) return pool.slice(0, 80)
  return pool.filter(item => `${item.name || ''} ${item.path || ''} ${item.summary || ''}`.toLowerCase().includes(keyword)).slice(0, 80)
})
const canRunKnowledgeOnScope = computed(() => knowledgeScopeType.value === 'ALL' || (knowledgeScopeType.value === 'FILE' ? knowledgeScopeIds.value.length > 0 : Boolean(knowledgeScopeId.value)))
const modeTitle = computed(() => {
  if (viewMode.value === 'trash') return '回收站'
  if (viewMode.value === 'search') return `搜索结果：${searchKeyword.value}`
  if (viewMode.value === 'gallery') return '图片相册'
  if (viewMode.value === 'admin') return '管理后台'
  if (viewMode.value === 'knowledge') return 'AI知识库问答'
  return '我的文件'
})

const isRootFiles = computed(() => viewMode.value === 'files' && currentParentId.value == null)
const currentFolderName = computed(() => breadcrumb.value[breadcrumb.value.length - 1]?.name || '我的文件')
const activeFileItems = computed(() => items.value.filter(item => item.kind === 'FILE'))
const activeFolderItems = computed(() => items.value.filter(item => item.kind === 'FOLDER'))
const recentActiveItems = computed(() => [...items.value]
  .sort((a, b) => new Date(b.updatedAt || b.createdAt || b.deletedAt || 0) - new Date(a.updatedAt || a.createdAt || a.deletedAt || 0))
  .slice(0, 5))
const imageFileCount = computed(() => activeFileItems.value.filter(item => fileTypeClass(item) === 'image').length)
const videoFileCount = computed(() => activeFileItems.value.filter(item => fileTypeClass(item) === 'video').length)
const documentFileCount = computed(() => activeFileItems.value.filter(item => ['pdf', 'word', 'text', 'ppt', 'sheet', 'doc'].includes(fileTypeClass(item))).length)
const rootDashboardStats = computed(() => [
  { label: '当前目录文件', value: activeFileItems.value.length, hint: `${activeFolderItems.value.length} 个文件夹`, icon: FileText },
  { label: '图片资料', value: imageFileCount.value, hint: '可进入相册集中查看', icon: Images },
  { label: '文档资料', value: documentFileCount.value, hint: '摘要与标签自动生成', icon: FileText },
  { label: '视频资料', value: videoFileCount.value, hint: '支持在线播放与倍速', icon: Video }
])
const adminTotalUsers = computed(() => storageOverview.value?.userCount || adminUsers.value.length || 0)
const adminTotalFiles = computed(() => storageOverview.value?.activeFileCount || 0)
const adminTotalFolders = computed(() => storageOverview.value?.activeFolderCount || 0)
const adminAbnormalUsers = computed(() => adminUsers.value.filter(u => Number(u.abnormalCount || 0) > 0).length)
const adminAbnormalFiles = computed(() => adminUsers.value.reduce((sum, u) => sum + Number(u.abnormalCount || 0), 0))
const adminDisabledUsers = computed(() => adminUsers.value.filter(u => !u.enabled).length)
const adminSystemStatus = computed(() => storageOverview.value?.statusText || '等待读取服务器状态')
const adminTopRiskUsers = computed(() => [...adminUsers.value]
  .sort((a, b) => Number(b.abnormalCount || 0) - Number(a.abnormalCount || 0))
  .filter(u => Number(u.abnormalCount || 0) > 0 || !u.enabled)
  .slice(0, 5))
const adminKpis = computed(() => [
  { label: '总用户数', value: adminTotalUsers.value.toLocaleString(), trend: `封禁 ${adminDisabledUsers.value}`, icon: Users },
  { label: '总文件数', value: adminTotalFiles.value.toLocaleString(), trend: `${adminTotalFolders.value.toLocaleString()} 个文件夹`, icon: FileText },
  { label: '服务器已用', value: formatSize(storageOverview.value?.serverUsedBytes || 0), trend: `占用 ${formatPercent(storageOverview.value?.serverUsedPercent || 0)}`, icon: Cloud },
  { label: '异常文件', value: adminAbnormalFiles.value.toLocaleString(), trend: `${adminAbnormalUsers.value} 个用户需关注`, icon: AlertTriangle, danger: adminAbnormalFiles.value > 0 },
  { label: '回收站占用', value: formatSize(storageOverview.value?.deletedFileBytes || 0), trend: `${storageOverview.value?.deletedFileCount || 0} 个文件`, icon: Trash2 }
])


function clearKnowledgeChat() {
  knowledgeMessages.value = []
  knowledgeQuestion.value = ''
}

function knowledgeRequestPayload(question = null, overview = false) {
  const payload = { topK: overview ? 50 : 8, scopeType: knowledgeScopeType.value }
  if (question !== null) payload.question = question
  if (knowledgeScopeType.value === 'FILE') {
    payload.scopeIds = knowledgeScopeIds.value
    payload.scopeId = knowledgeScopeIds.value[0] || null
  } else if (knowledgeScopeType.value === 'FOLDER') {
    payload.scopeId = knowledgeScopeId.value
  }
  return payload
}

function knowledgeScopeTypeLabel(type = knowledgeScopeType.value) {
  if (type === 'FILE') return '指定文件'
  if (type === 'FOLDER') return '指定文件夹'
  return '全部文件'
}

function setKnowledgeScope(type) {
  knowledgeScopeType.value = type
  knowledgeScopeId.value = null
  knowledgeScopeIds.value = []
  knowledgeSourceKeyword.value = ''
}

function isKnowledgeSourceSelected(item) {
  if (!item) return false
  return knowledgeScopeType.value === 'FILE'
    ? knowledgeScopeIds.value.includes(item.id)
    : knowledgeScopeId.value === item.id
}

function selectKnowledgeSource(item) {
  if (!item) return
  if (knowledgeScopeType.value === 'FILE') {
    knowledgeScopeIds.value = knowledgeScopeIds.value.includes(item.id)
      ? knowledgeScopeIds.value.filter(id => id !== item.id)
      : [...knowledgeScopeIds.value, item.id]
  } else {
    knowledgeScopeId.value = item.id
  }
}

function clearSelectedKnowledgeFiles() {
  knowledgeScopeIds.value = []
}

async function scrollKnowledgeToBottom() {
  await nextTick()
  if (knowledgeScrollRef.value) {
    knowledgeScrollRef.value.scrollTop = knowledgeScrollRef.value.scrollHeight
  }
}

function clearRecommend() {
  focusFile.value = null
  sideRelatedItems.value = []
  collapsedRecommendGroups.value = []
}

function isRecommendGroupClosed(name) {
  return collapsedRecommendGroups.value.includes(name)
}

function toggleRecommendGroup(name) {
  collapsedRecommendGroups.value = isRecommendGroupClosed(name)
    ? collapsedRecommendGroups.value.filter(item => item !== name)
    : [...collapsedRecommendGroups.value, name]
}

function clearSelected() {
  selectedIds.value = []
}

function isSelected(item) {
  return selectedIds.value.includes(item.id)
}

function toggleSelected(item, event) {
  if (event) event.stopPropagation()
  if (isSelected(item)) selectedIds.value = selectedIds.value.filter(id => id !== item.id)
  else selectedIds.value = [...selectedIds.value, item.id]
}

function toggleSelectAllVisible() {
  const selectable = items.value.map(item => item.id)
  const allSelected = selectable.length > 0 && selectable.every(id => selectedIds.value.includes(id))
  selectedIds.value = allSelected ? [] : selectable
}

function setMessage(text) {
  message.value = text
  if (text) setTimeout(() => { if (message.value === text) message.value = '' }, 6000)
}

function percentage(used, quota) {
  if (!quota) return 0
  return Math.min(100, Math.round((used / quota) * 100))
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = bytes
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  return `${size.toFixed(size >= 10 || index === 0 ? 0 : 1)} ${units[index]}`
}


function formatPercent(value) {
  const n = Number(value || 0)
  if (!Number.isFinite(n)) return '0%'
  return `${n.toFixed(n >= 10 ? 0 : 1)}%`
}


function percentWidth(value) {
  const n = Number(value || 0)
  if (!Number.isFinite(n)) return '0%'
  return `${Math.max(0, Math.min(100, n))}%`
}

function storageStatusClass(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'DANGER') return 'bad'
  if (value === 'WARNING') return 'warning'
  return ''
}

function storageStatusLabel(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'DANGER') return '容量紧张'
  if (value === 'WARNING') return '需要关注'
  return '容量正常'
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function formatDate(value) {
  if (!value) return '未知时间'
  const d = new Date(value)
  return `${d.getFullYear()}年${String(d.getMonth() + 1).padStart(2, '0')}月`
}

function tagText(tags) {
  return Array.isArray(tags) && tags.length ? tags.join(' / ') : '-'
}

function tagList(tags) {
  if (!Array.isArray(tags)) return []
  return tags.filter(Boolean).slice(0, 6)
}

function firstUsefulTag(tags) {
  const list = tagList(tags)
  return list.find(t => !['PDF', '图片', '文件', '文档', '文本资料'].includes(String(t))) || list[0] || '相似资料'
}

function buildRecommendGroups(baseFile, related) {
  const list = Array.isArray(related) ? related : []
  if (!baseFile || !list.length) return []
  const baseTags = new Set(tagList(baseFile.tags).map(t => String(t).toLowerCase()))
  const buckets = new Map()
  for (const item of list) {
    const sameTag = tagList(item.tags).find(t => baseTags.has(String(t).toLowerCase()))
    const key = sameTag || firstUsefulTag(item.tags)
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(item)
  }
  return Array.from(buckets.entries()).map(([name, files]) => ({ name, files })).slice(0, 6)
}

function setFileDisplayMode(mode) {
  fileDisplayMode.value = mode
  localStorage.setItem('cloudmind-file-display', mode)
}

function fileTypeClass(item) {
  if (!item || item.kind === 'FOLDER') return 'folder'
  const ct = (item.contentType || '').toLowerCase()
  const name = (item.name || '').toLowerCase()
  if (ct.startsWith('image/') || /\.(png|jpg|jpeg|gif|webp|bmp|svg)$/.test(name)) return 'image'
  if (ct.startsWith('video/') || /\.(mp4|webm|ogg|mov|mkv|avi)$/.test(name)) return 'video'
  if (ct.startsWith('audio/') || /\.(mp3|wav|ogg|flac|m4a)$/.test(name)) return 'audio'
  if (name.endsWith('.pdf')) return 'pdf'
  if (/\.(doc|docx)$/.test(name)) return 'word'
  if (/\.(ppt|pptx)$/.test(name)) return 'ppt'
  if (/\.(xls|xlsx|csv)$/.test(name)) return 'sheet'
  if (/\.(zip|rar|7z|tar|gz)$/.test(name)) return 'archive'
  if (/\.(txt|md|json|xml|java|js|vue|css|html|py)$/.test(name)) return 'text'
  return 'doc'
}

function fileTypeLabel(item) {
  const map = {
    folder: '文件夹', image: '图片', video: '视频', audio: '音频', pdf: 'PDF', word: 'Word',
    ppt: '演示文稿', sheet: '表格', archive: '压缩包', text: '文本', doc: '文件'
  }
  return map[fileTypeClass(item)] || '文件'
}

function shortName(value) {
  const str = String(value || '')
  if (!str) return 'U'
  return str.slice(0, 2).toUpperCase()
}

async function quickSearch(keyword) {
  searchKeyword.value = keyword
  await searchFiles()
}

function canShowImageThumb(item) {
  const ct = (item.contentType || '').toLowerCase()
  const name = (item.name || '').toLowerCase()
  return item.kind === 'FILE' && (ct.startsWith('image/') || /\.(png|jpg|jpeg|gif|webp)$/.test(name))
}

function clearThumbnailUrls() {
  thumbnailGeneration += 1
  Object.values(thumbnailUrls.value).forEach(url => URL.revokeObjectURL(url))
  thumbnailUrls.value = {}
}

function hydrateThumbnails(nextItems) {
  const generation = thumbnailGeneration
  nextItems.filter(canShowImageThumb).forEach(async item => {
    try {
      const response = await request(`/files/${item.id}/download?disposition=inline`)
      const objectUrl = URL.createObjectURL(await response.blob())
      if (generation !== thumbnailGeneration) {
        URL.revokeObjectURL(objectUrl)
        return
      }
      thumbnailUrls.value = { ...thumbnailUrls.value, [item.id]: objectUrl }
    } catch {
      // A failed thumbnail must not block the file list.
    }
  })
}

function replaceItems(nextItems) {
  clearThumbnailUrls()
  items.value = Array.isArray(nextItems) ? nextItems : []
  hydrateThumbnails(items.value)
}

function releasePreviewObjectUrl() {
  if (activePreviewObjectUrl.value) {
    URL.revokeObjectURL(activePreviewObjectUrl.value)
    activePreviewObjectUrl.value = ''
  }
}

function closePreview() {
  previewVisible.value = false
  releasePreviewObjectUrl()
}

function reviewText(status) {
  const value = (status || 'NORMAL').toUpperCase()
  if (value === 'ABNORMAL') return '异常'
  if (value === 'PENDING') return '未审查'
  return '正常'
}

function reviewClass(status) {
  const value = (status || 'NORMAL').toUpperCase()
  if (value === 'ABNORMAL') return 'bad'
  if (value === 'PENDING') return 'pending'
  return ''
}

function bytesToGb(bytes) {
  const value = Number(bytes || 0)
  return Math.round((value / 1024 / 1024 / 1024) * 100) / 100
}

function quotaBytesFromGb(gb) {
  const value = Number(gb || 0)
  if (!Number.isFinite(value) || value <= 0) return 1024 * 1024 * 1024
  return Math.round(value * 1024 * 1024 * 1024)
}

function formatGbInputLabel(bytes) {
  const gb = bytesToGb(bytes)
  return `${gb.toLocaleString()} GB`
}

function normalizeAdminUser(u) {
  return { ...u, quotaGb: bytesToGb(u.quotaBytes) }
}

function accountStatusText(u) {
  return u?.enabled ? '账号正常' : '账号已封禁'
}

function accountStatusClass(u) {
  return u?.enabled ? '' : 'bad'
}

function contentStatusText(u) {
  return Number(u?.abnormalCount || 0) > 0 ? '内容异常' : '内容正常'
}

function contentStatusClass(u) {
  return Number(u?.abnormalCount || 0) > 0 ? 'warning' : ''
}

function auditUserNodeClass(u) {
  return {
    selected: currentAuditUser.value?.id === u.id,
    banned: !u.enabled,
    abnormal: Number(u?.abnormalCount || 0) > 0,
    clean: u.enabled && Number(u?.abnormalCount || 0) === 0
  }
}

function folderReviewTip(item) {
  if (item.kind !== 'FOLDER') return item.reviewNote || ''
  const abnormal = Number(item.childAbnormalCount || 0)
  const pending = Number(item.childPendingCount || 0)
  if (abnormal > 0) return `下级包含 ${abnormal} 个异常文件${pending > 0 ? `，${pending} 个待审查文件` : ''}`
  if (pending > 0) return `下级包含 ${pending} 个待审查文件`
  return item.reviewNote || '下级文件审查正常'
}

function applyAuthSession(data) {
  token.value = data?.accessToken || data?.token || ''
  refreshToken.value = data?.refreshToken || ''
  user.value = data?.user || null
  if (token.value) sessionStorage.setItem('cloudmind-token', token.value)
  else sessionStorage.removeItem('cloudmind-token')
  if (refreshToken.value) sessionStorage.setItem('cloudmind-refresh-token', refreshToken.value)
  else sessionStorage.removeItem('cloudmind-refresh-token')
  if (user.value) sessionStorage.setItem('cloudmind-user', JSON.stringify(user.value))
  else sessionStorage.removeItem('cloudmind-user')
}

function clearAuthState() {
  token.value = ''
  refreshToken.value = ''
  user.value = null
  replaceItems([])
  releasePreviewObjectUrl()
  sessionStorage.removeItem('cloudmind-token')
  sessionStorage.removeItem('cloudmind-refresh-token')
  sessionStorage.removeItem('cloudmind-user')
  localStorage.removeItem('cloudmind-token')
  localStorage.removeItem('cloudmind-user')
}

async function refreshAccessToken() {
  if (!refreshToken.value) return false
  if (!refreshSessionPromise) {
    refreshSessionPromise = fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: refreshToken.value })
    }).then(async response => {
      if (!response.ok) return false
      const result = await response.json()
      applyAuthSession(result.data)
      return Boolean(token.value)
    }).catch(() => false).finally(() => {
      refreshSessionPromise = null
    })
  }
  return refreshSessionPromise
}

async function request(path, options = {}) {
  const { skipAuthRefresh = false, ...fetchOptions } = options
  const headers = fetchOptions.headers ? { ...fetchOptions.headers } : {}
  if (token.value) headers.Authorization = `Bearer ${token.value}`
  const response = await fetch(`${API_BASE}${path}`, { ...fetchOptions, headers })
  if (response.status === 401 && refreshToken.value && !skipAuthRefresh && path !== '/auth/refresh') {
    const refreshed = await refreshAccessToken()
    if (refreshed) return request(path, { ...options, skipAuthRefresh: true })
    clearAuthState()
  }
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok) {
    if (contentType.includes('application/json')) {
      const errorBody = await response.json()
      throw new Error(errorBody.message || '请求失败')
    }
    throw new Error(await response.text())
  }
  if (contentType.includes('application/json')) return response.json()
  return response
}

function applyQuota(data) {
  usedBytes.value = data.usedBytes || 0
  quotaBytes.value = data.quotaBytes || quotaBytes.value || 0
  remainingBytes.value = data.remainingBytes ?? Math.max(0, quotaBytes.value - usedBytes.value)
  elasticExtraBytes.value = data.elasticExtraBytes || Math.floor((quotaBytes.value || 0) / 2)
}

async function login() {
  loading.value = true
  try {
    const res = await request('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    applyAuthSession(res.data)
    setMessage('登录成功')
    password.value = ''
    if (!mustChangePassword.value) await loadFiles(null, true)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function register() {
  loading.value = true
  try {
    const res = await request('/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    applyAuthSession(res.data)
    setMessage('注册成功')
    password.value = ''
    await loadFiles(null, true)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function changePassword() {
  if (newPassword.value !== confirmPassword.value) {
    setMessage('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    const res = await request('/auth/change-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentPassword: currentPassword.value,
        newPassword: newPassword.value
      })
    })
    applyAuthSession(res.data)
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    setMessage('密码已修改，请妥善保管新密码')
    await loadFiles(null, true)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function logout() {
  const accessToken = token.value
  const sessionRefreshToken = refreshToken.value
  try {
    await fetch(`${API_BASE}/auth/logout`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
      },
      body: JSON.stringify({ refreshToken: sessionRefreshToken || null })
    })
  } finally {
    clearAuthState()
  }
}

function sessionDeviceName(userAgent) {
  const value = String(userAgent || '')
  if (!value) return '未知设备'
  const browser = value.includes('Edg/') ? 'Edge'
    : value.includes('Chrome/') ? 'Chrome'
      : value.includes('Firefox/') ? 'Firefox'
        : value.includes('Safari/') ? 'Safari'
          : '浏览器'
  const system = value.includes('Windows') ? 'Windows'
    : value.includes('Android') ? 'Android'
      : value.includes('iPhone') || value.includes('iPad') ? 'iOS / iPadOS'
        : value.includes('Mac OS') ? 'macOS'
          : value.includes('Linux') ? 'Linux'
            : '未知系统'
  return `${browser} · ${system}`
}

async function openSessionDialog() {
  sessionDialog.value = { visible: true, loading: true, error: '', items: [] }
  await nextTick()
  sessionCloseButton.value?.focus()
  try {
    const res = await request('/auth/sessions')
    sessionDialog.value.items = Array.isArray(res.data) ? res.data : []
  } catch (e) {
    sessionDialog.value.error = e.message
  } finally {
    sessionDialog.value.loading = false
  }
}

function closeSessionDialog() {
  sessionDialog.value.visible = false
  nextTick(() => sessionSecurityButton.value?.focus())
}

async function revokeDeviceSession(session) {
  if (!session || session.current || sessionDialog.value.loading) return
  sessionDialog.value.loading = true
  sessionDialog.value.error = ''
  try {
    await request(`/auth/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' })
    sessionDialog.value.items = sessionDialog.value.items.filter(item => item.id !== session.id)
    setMessage('指定设备已安全下线')
  } catch (e) {
    sessionDialog.value.error = e.message
  } finally {
    sessionDialog.value.loading = false
  }
}

async function loadFiles(parentId = currentParentId.value, resetBreadcrumb = false) {
  if (!token.value) return
  loading.value = true
  try {
    viewMode.value = 'files'
    const query = parentId == null ? '' : `?parentId=${parentId}`
    const res = await request(`/files${query}`)
    replaceItems(res.data.items)
    clearSelected()
    clearRecommend()
    applyQuota(res.data)
    if (resetBreadcrumb) breadcrumb.value = [{ id: null, name: '我的文件' }]
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadTrash() {
  loading.value = true
  try {
    const res = await request('/files/trash')
    replaceItems(res.data)
    clearSelected()
    clearRecommend()
    viewMode.value = 'trash'
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadGallery() {
  loading.value = true
  try {
    const res = await request('/files/gallery')
    replaceItems(res.data)
    clearSelected()
    clearRecommend()
    viewMode.value = 'gallery'
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openFolder(item) {
  if (viewMode.value !== 'files') return
  breadcrumb.value.push({ id: item.id, name: item.name })
  await loadFiles(item.id)
}

async function openItem(item) {
  if (!item || viewMode.value === 'trash') return
  if (item.kind === 'FOLDER') {
    await openFolder(item)
    return
  }
  await previewFile(item)
}

async function goBreadcrumb(index) {
  breadcrumb.value = breadcrumb.value.slice(0, index + 1)
  await loadFiles(currentParentId.value)
}

async function createFolder() {
  const name = prompt('请输入文件夹名称')
  if (!name) return
  loading.value = true
  try {
    await request('/files/folder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId: currentParentId.value, name })
    })
    setMessage('文件夹创建成功')
    await loadFiles()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function uploadFileList(files) {
  const list = Array.from(files || []).filter(Boolean)
  if (!list.length) return
  loading.value = true
  try {
    for (const file of list) {
      const form = new FormData()
      form.append('file', file)
      const parentQuery = currentParentId.value == null ? '' : `?parentId=${currentParentId.value}`
      await request(`/files/upload${parentQuery}`, { method: 'POST', body: form })
    }
    setMessage('上传成功；系统已自动生成摘要和标签，文件审查状态仅管理员可见。')
    await loadFiles()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function uploadFolderPayload(entries) {
  const list = Array.from(entries || []).filter(e => e?.file)
  if (!list.length) return
  loading.value = true
  try {
    const form = new FormData()
    list.forEach(entry => {
      form.append('files', entry.file)
      form.append('relativePaths', entry.path || entry.file.name)
    })
    const parentQuery = currentParentId.value == null ? '' : `?parentId=${currentParentId.value}`
    await request(`/files/upload-folder${parentQuery}`, { method: 'POST', body: form })
    setMessage('文件夹上传成功；系统已自动生成摘要和标签。')
    await loadFiles()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function uploadFiles(event) {
  await uploadFileList(event.target.files || [])
  if (fileInput.value) fileInput.value.value = ''
}

async function uploadFolder(event) {
  const files = Array.from(event.target.files || [])
  await uploadFolderPayload(files.map(file => ({ file, path: file.webkitRelativePath || file.name })))
  if (folderInput.value) folderInput.value.value = ''
}

function readEntries(reader) {
  return new Promise((resolve, reject) => reader.readEntries(resolve, reject))
}

function fileFromEntry(entry) {
  return new Promise((resolve, reject) => entry.file(resolve, reject))
}

async function collectEntry(entry, prefix, output) {
  if (!entry) return
  if (entry.isFile) {
    const file = await fileFromEntry(entry)
    output.push({ file, path: `${prefix}${file.name}` })
    return
  }
  if (entry.isDirectory) {
    const reader = entry.createReader()
    let batch = []
    do {
      batch = await readEntries(reader)
      for (const child of batch) {
        await collectEntry(child, `${prefix}${entry.name}/`, output)
      }
    } while (batch.length)
  }
}

function hasFileDrag(event) {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function beginDragUpload(event) {
  if (!canDragUpload.value || !hasFileDrag(event)) return
  dragDepth.value += 1
  dragActive.value = true
}

function keepDragUpload(event) {
  if (!canDragUpload.value || !hasFileDrag(event)) return
  dragActive.value = true
}

function leaveDragUpload() {
  if (!dragActive.value) return
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) dragActive.value = false
}

async function handleDrop(event) {
  dragDepth.value = 0
  dragActive.value = false
  if (!canDragUpload.value) return
  const dataTransfer = event.dataTransfer
  const items = Array.from(dataTransfer?.items || [])
  const entries = items.map(item => item.webkitGetAsEntry ? item.webkitGetAsEntry() : null).filter(Boolean)
  const hasDirectory = entries.some(entry => entry.isDirectory)
  if (entries.length && hasDirectory) {
    const collected = []
    for (const entry of entries) await collectEntry(entry, '', collected)
    await uploadFolderPayload(collected)
  } else {
    await uploadFileList(Array.from(dataTransfer?.files || []))
  }
}


async function focusForRecommendation(item, event = null) {
  if (event?.target?.closest?.('button,input,a,label')) return
  if (!item || item.kind !== 'FILE' || viewMode.value === 'trash') return
  focusFile.value = item
  sideRecommendLoading.value = true
  try {
    const res = await request(`/files/${item.id}/related`)
    sideRelatedItems.value = res.data || []
    collapsedRecommendGroups.value = []
  } catch (e) {
    sideRelatedItems.value = []
    setMessage(e.message)
  } finally {
    sideRecommendLoading.value = false
  }
}

async function previewRecommended(item) {
  focusFile.value = item
  await previewFile(item)
}

async function analyzeItem(item) {
  if (item.kind !== 'FILE') return
  loading.value = true
  try {
    await request(`/files/${item.id}/analyze`, { method: 'POST' })
    setMessage('摘要和标签已重新生成')
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function downloadFile(item, admin = false) {
  loading.value = true
  try {
    const path = admin ? `/admin/files/${item.id}/download` : `/files/${item.id}/download`
    const response = await request(path)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = item.name
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function authenticatedObjectUrl(path) {
  const response = await request(path)
  return URL.createObjectURL(await response.blob())
}

function openPreviewDataInNewTab(data, previewWindow = null) {
  const type = data.previewType
  if (['PDF', 'IMAGE', 'VIDEO', 'AUDIO'].includes(type)) {
    const w = previewWindow || window.open('', '_blank')
    if (!w) return false
    w.location.href = data.inlineUrl
    return true
  }
  if (type === 'TEXT') {
    const w = previewWindow || window.open('', '_blank')
    if (!w) return false
    const escapedName = escapeHtml(data.name || '文本预览')
    const escapedText = escapeHtml(data.text || '暂未提取到文本内容。')
    w.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${escapedName}</title><style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;margin:0;background:#f8fafc;color:#0f172a;}header{position:sticky;top:0;background:white;border-bottom:1px solid #e2e8f0;padding:16px 24px;}pre{white-space:pre-wrap;line-height:1.8;margin:0;padding:24px;font-size:16px;}</style></head><body><header><h2>${escapedName}</h2></header><pre>${escapedText}</pre></body></html>`)
    w.document.close()
    return true
  }
  return false
}

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

async function previewFile(item, admin = false) {
  loading.value = true
  const previewWindow = window.open('', '_blank')
  try {
    adminPreviewMode.value = admin
    const res = await request(admin ? `/admin/files/${item.id}/preview` : `/files/${item.id}/preview`)
    const data = res.data
    releasePreviewObjectUrl()
    if (['PDF', 'IMAGE', 'VIDEO', 'AUDIO'].includes(data.previewType) && data.inlinePath) {
      data.inlineUrl = await authenticatedObjectUrl(data.inlinePath)
      activePreviewObjectUrl.value = data.inlineUrl
    }
    if (!admin) {
      focusFile.value = item
      request(`/files/${item.id}/related`).then(r => { sideRelatedItems.value = r.data || []
      collapsedRecommendGroups.value = [] }).catch(() => {})
    }
    if (openPreviewDataInNewTab(data, previewWindow)) {
      const openedObjectUrl = activePreviewObjectUrl.value
      activePreviewObjectUrl.value = ''
      if (openedObjectUrl) window.setTimeout(() => URL.revokeObjectURL(openedObjectUrl), 300000)
      setMessage('已在新窗口打开预览')
      return
    }
    if (previewWindow) previewWindow.close()
    previewData.value = data
    previewVisible.value = true
    playbackRate.value = 1
    if (!admin) {
      const [relatedRes, versionsRes] = await Promise.all([
        request(`/files/${item.id}/related`),
        request(`/files/${item.id}/versions`)
      ])
      relatedItems.value = relatedRes.data
      versions.value = versionsRes.data
    } else {
      relatedItems.value = []
      versions.value = []
    }
    await nextTick()
    applyPlaybackRate()
  } catch (e) {
    if (previewWindow) previewWindow.close()
    releasePreviewObjectUrl()
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

function applyPlaybackRate() {
  if (mediaRef.value) mediaRef.value.playbackRate = Number(playbackRate.value || 1)
}

async function renameItem(item) {
  const name = prompt('请输入新名称', item.name)
  if (!name || name === item.name) return
  loading.value = true
  try {
    await request(`/files/${item.id}/rename`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    })
    setMessage('重命名成功')
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

function confirmDanger(text) {
  if (!confirm(text)) return false
  const typed = prompt('这是危险操作。请输入“确认”两个字继续。')
  return typed === '确认'
}

function confirmLight(text) {
  return confirm(text)
}

async function deleteItem(item) {
  if (!confirmLight(`确定把「${item.name}」移动到回收站吗？可以在回收站恢复。`)) return
  loading.value = true
  try {
    await request(`/files/${item.id}`, { method: 'DELETE' })
    setMessage('已移动到回收站')
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}


async function batchDeleteSelected() {
  if (!selectedItems.value.length) return
  if (!confirmLight(`确定把选中的 ${selectedItems.value.length} 个项目移动到回收站吗？可以在回收站恢复。`)) return
  loading.value = true
  try {
    for (const item of selectedItems.value) {
      await request(`/files/${item.id}`, { method: 'DELETE' })
    }
    setMessage(`已将 ${selectedItems.value.length} 个项目移动到回收站`)
    clearSelected()
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openBatchTargetDialog(type) {
  if (!selectedItems.value.length) return
  loading.value = true
  try {
    const res = await request('/files/folders')
    const selectedSet = new Set(selectedIds.value)
    targetDialog.value = {
      visible: true,
      type,
      item: null,
      items: [...selectedItems.value],
      targetParentId: null,
      folders: res.data.filter(f => !selectedSet.has(f.id))
    }
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function restoreItem(item) {
  loading.value = true
  try {
    await request(`/files/${item.id}/restore`, { method: 'POST' })
    setMessage('恢复成功')
    await loadTrash()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function purgeItem(item) {
  if (!confirmDanger(`永久删除「${item.name}」？这个操作不能恢复。`)) return
  loading.value = true
  try {
    await request(`/files/${item.id}/permanent`, { method: 'DELETE' })
    setMessage('已永久删除')
    await loadTrash()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openTargetDialog(type, item) {
  loading.value = true
  try {
    const res = await request('/files/folders')
    targetDialog.value = {
      visible: true,
      type,
      item,
      items: [item],
      targetParentId: null,
      folders: res.data.filter(f => f.id !== item.id)
    }
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function confirmTargetAction() {
  const dialog = targetDialog.value
  const targets = dialog.items && dialog.items.length ? dialog.items : (dialog.item ? [dialog.item] : [])
  if (!targets.length) return
  loading.value = true
  try {
    for (const item of targets) {
      const path = dialog.type === 'copy' ? `/files/${item.id}/copy` : `/files/${item.id}/move`
      const method = dialog.type === 'copy' ? 'POST' : 'PUT'
      await request(path, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ targetParentId: dialog.targetParentId || null })
      })
    }
    setMessage(dialog.type === 'copy' ? `已复制 ${targets.length} 个项目` : `已移动 ${targets.length} 个项目`)
    targetDialog.value.visible = false
    clearSelected()
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function searchFiles() {
  if (!searchKeyword.value.trim()) {
    await loadFiles()
    return
  }
  loading.value = true
  try {
    const res = await request(`/files/search?q=${encodeURIComponent(searchKeyword.value.trim())}`)
    replaceItems(res.data)
    clearSelected()
    clearRecommend()
    viewMode.value = 'search'
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openKnowledge() {
  viewMode.value = 'knowledge'
  clearSelected()
  clearRecommend()
  await loadKnowledgeSources()
}

async function loadKnowledgeSources() {
  if (knowledgeSourcesLoading.value) return
  knowledgeSourcesLoading.value = true
  try {
    const res = await request('/knowledge/sources')
    knowledgeSources.value = {
      files: res.data.files || [],
      folders: res.data.folders || [],
      fileCount: res.data.fileCount || 0,
      folderCount: res.data.folderCount || 0,
      knowledgeFileCount: res.data.knowledgeFileCount || 0
    }
  } catch (e) {
    setMessage('知识库来源加载失败：' + e.message)
  } finally {
    knowledgeSourcesLoading.value = false
  }
}

function askPresetQuestion(question) {
  knowledgeQuestion.value = question
  askKnowledgeQuestion()
}

async function askKnowledgeQuestion() {
  const question = knowledgeQuestion.value.trim()
  if (!question || knowledgeLoading.value) return
  if (!canRunKnowledgeOnScope.value) {
    setMessage('请先选择文件或文件夹')
    return
  }
  knowledgeMessages.value.push({ role: 'user', content: question, scopeLabel: knowledgeScopeLabel.value })
  knowledgeQuestion.value = ''
  knowledgeLoading.value = true
  await scrollKnowledgeToBottom()
  try {
    const res = await request('/knowledge/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(knowledgeRequestPayload(question))
    })
    knowledgeMessages.value.push({
      role: 'assistant',
      content: res.data.answer,
      usedAi: res.data.usedAi,
      scope: res.data.scope,
      sources: res.data.sources || [],
      suggestions: res.data.suggestions || []
    })
  } catch (e) {
    knowledgeMessages.value.push({ role: 'assistant', content: '问答失败：' + e.message, error: true, sources: [] })
    setMessage(e.message)
  } finally {
    knowledgeLoading.value = false
    await scrollKnowledgeToBottom()
  }
}

async function generateKnowledgeOverview() {
  if (knowledgeOverviewLoading.value || knowledgeLoading.value) return
  if (!canRunKnowledgeOnScope.value) {
    setMessage('请先选择文件或文件夹')
    return
  }
  const title = `生成${knowledgeScopeTypeLabel()}概述：${knowledgeScopeLabel.value}`
  knowledgeMessages.value.push({ role: 'user', content: title, scopeLabel: knowledgeScopeLabel.value })
  knowledgeOverviewLoading.value = true
  await scrollKnowledgeToBottom()
  try {
    const res = await request('/knowledge/overview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(knowledgeRequestPayload(null, true))
    })
    knowledgeMessages.value.push({
      role: 'assistant',
      content: res.data.answer,
      usedAi: res.data.usedAi,
      scope: res.data.scope,
      sources: res.data.sources || [],
      suggestions: res.data.suggestions || []
    })
  } catch (e) {
    knowledgeMessages.value.push({ role: 'assistant', content: '概述生成失败：' + e.message, error: true, sources: [] })
    setMessage(e.message)
  } finally {
    knowledgeOverviewLoading.value = false
    await scrollKnowledgeToBottom()
  }
}

async function reloadCurrentView() {
  if (viewMode.value === 'trash') await loadTrash()
  else if (viewMode.value === 'search') await searchFiles()
  else if (viewMode.value === 'gallery') await loadGallery()
  else if (viewMode.value === 'knowledge') await openKnowledge()
  else if (viewMode.value === 'admin') await openAdmin(adminTab.value)
  else await loadFiles()
}

async function restoreVersion(version) {
  if (!previewData.value) return
  if (!confirmDanger(`恢复到 ${formatTime(version.createdAt)} 的历史版本吗？当前版本会自动保存为历史版本。`)) return
  loading.value = true
  try {
    await request(`/files/${previewData.value.id}/versions/${version.id}/restore`, { method: 'POST' })
    setMessage('历史版本恢复成功')
    await previewFile(previewData.value)
    await reloadCurrentView()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openAdmin(tab = 'dashboard') {
  if (!isAdmin.value) return
  viewMode.value = 'admin'
  clearRecommend()
  adminTab.value = tab
  if (tab === 'dashboard') await loadAdminDashboard()
  if (tab === 'users') await loadAdminUsers()
  if (tab === 'audit') await loadAuditUsers()
  if (tab === 'storage') await loadAdminStorageOverview()
  if (tab === 'ai') await loadAiConfig()
}

async function loadAdminDashboard() {
  loading.value = true
  try {
    const [usersRes, auditRes, storageRes] = await Promise.all([
      request('/admin/users'),
      request('/admin/audit/users'),
      request('/admin/storage/overview')
    ])
    adminUsers.value = (usersRes.data || []).map(normalizeAdminUser)
    auditUsers.value = (auditRes.data || []).map(normalizeAdminUser)
    storageOverview.value = storageRes.data || null
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadAdminUsers() {
  loading.value = true
  try {
    const res = await request('/admin/users')
    adminUsers.value = (res.data || []).map(normalizeAdminUser)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadAdminStorageOverview() {
  loading.value = true
  try {
    const res = await request('/admin/storage/overview')
    storageOverview.value = res.data || null
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function createAdminUser() {
  loading.value = true
  try {
    await request('/admin/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: adminCreateForm.value.username,
        password: adminCreateForm.value.password,
        role: adminCreateForm.value.role,
        quotaBytes: quotaBytesFromGb(adminCreateForm.value.quotaGb)
      })
    })
    setMessage('用户创建成功')
    adminCreateForm.value.username = ''
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

function openAdminUserEdit(u) {
  if (u.isSelf) return
  adminEditDialog.value = {
    visible: true,
    user: u,
    role: u.role || 'USER',
    quotaGb: bytesToGb(u.quotaBytes) || 10
  }
}

async function confirmUpdateAdminUser() {
  const dialog = adminEditDialog.value
  const u = dialog.user
  if (!u || u.isSelf) return
  loading.value = true
  try {
    await request(`/admin/users/${u.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: dialog.role, quotaBytes: quotaBytesFromGb(dialog.quotaGb) })
    })
    setMessage(`已更新 ${u.username} 的权限等级和存储空间`)
    adminEditDialog.value.visible = false
    await loadAdminUsers()
    await loadAuditUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (_) {
    try {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      const ok = document.execCommand('copy')
      textarea.remove()
      return ok
    } catch (__) {
      return false
    }
  }
}

async function resetPassword(u) {
  if (u.isSelf) return
  if (!confirmDanger(`确定重置用户「${u.username}」的密码吗？`)) return
  loading.value = true
  try {
    const res = await request(`/admin/users/${u.id}/reset-password`, { method: 'POST' })
    const copied = await copyToClipboard(res.data.newPassword)
    setMessage(`密码已重置，新密码是：${res.data.newPassword}${copied ? '（已自动复制到剪贴板）' : '（自动复制失败，请手动复制）'}`)
    alert(`用户 ${u.username} 的新密码是：\n${res.data.newPassword}\n\n${copied ? '已自动复制到剪贴板。' : '自动复制失败，请手动复制保存。'}`)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function toggleUserEnabled(u) {
  if (u.isSelf) return
  const action = u.enabled ? '封禁' : '解封'
  if (!confirmDanger(`确定${action}用户「${u.username}」吗？`)) return
  loading.value = true
  try {
    await request(`/admin/users/${u.id}/enabled`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !u.enabled })
    })
    setMessage(`账号已${action}`)
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function deleteUser(u) {
  if (u.isSelf) return
  if (!confirmDanger(`确定删除用户「${u.username}」及其所有文件吗？这个操作不可恢复。`)) return
  loading.value = true
  try {
    await request(`/admin/users/${u.id}`, { method: 'DELETE' })
    setMessage('用户及文件已删除')
    await loadAdminUsers()
    await loadAuditUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadAuditUsers() {
  loading.value = true
  try {
    const res = await request('/admin/audit/users')
    auditUsers.value = (res.data || []).map(normalizeAdminUser)
    if (!currentAuditUser.value) auditItems.value = []
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openAuditUser(u) {
  currentAuditUser.value = u
  auditBreadcrumb.value = [{ id: null, name: u.username }]
  await loadAuditFiles(null)
}

async function loadAuditFiles(parentId = null) {
  if (!currentAuditUser.value) return
  loading.value = true
  try {
    const q = parentId == null ? '' : `?parentId=${parentId}`
    const res = await request(`/admin/audit/users/${currentAuditUser.value.id}/files${q}`)
    auditItems.value = res.data.items
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function openAuditFolder(item) {
  auditBreadcrumb.value.push({ id: item.id, name: item.name })
  await loadAuditFiles(item.id)
}

async function goAuditBreadcrumb(index) {
  auditBreadcrumb.value = auditBreadcrumb.value.slice(0, index + 1)
  const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
  await loadAuditFiles(id)
}

async function markReview(item, status) {
  loading.value = true
  try {
    await request(`/admin/files/${item.id}/review`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, note: status === 'ABNORMAL' ? '管理员手动标记异常' : '' })
    })
    setMessage('审查状态已更新')
    const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
    await loadAuditFiles(id)
    await loadAuditUsers()
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function loadAiConfig() {
  loading.value = true
  try {
    const res = await request('/admin/ai/config')
    aiConfig.value = { ...aiConfig.value, ...res.data, apiKey: '' }
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function saveAiConfig() {
  loading.value = true
  try {
    const body = { ...aiConfig.value }
    if (!body.apiKey) body.apiKey = 'KEEP_EXISTING'
    const res = await request('/admin/ai/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    aiConfig.value = { ...aiConfig.value, ...res.data, apiKey: '' }
    setMessage('AI 接口配置已保存')
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function testAiConfig() {
  loading.value = true
  aiTestMessage.value = ''
  try {
    const res = await request('/admin/ai/test', { method: 'POST' })
    aiTestMessage.value = res.data.message || '测试完成'
    setMessage(aiTestMessage.value)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function adminAnalyzeItem(item) {
  if (item.kind !== 'FILE') return
  loading.value = true
  try {
    await request(`/admin/files/${item.id}/analyze`, { method: 'POST' })
    setMessage('摘要和标签已重新生成')
    const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
    await loadAuditFiles(id)
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function adminAiReview(item) {
  if (item.kind !== 'FILE') return
  loading.value = true
  try {
    const res = await request(`/admin/files/${item.id}/ai-review`, { method: 'POST' })
    setMessage(`${item.name} 审查完成：${reviewText(res.data.status)} ${res.data.note || ''}`)
    const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
    await loadAuditFiles(id)
    await loadAuditUsers()
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

async function adminAiReviewAll() {
  if (!confirmDanger('确定对全站文件进行 AI/规则审查吗？文件较多时可能需要等待。')) return
  loading.value = true
  try {
    const res = await request('/admin/ai/review-all', { method: 'POST' })
    setMessage(`全站审查完成：共 ${res.data.total} 个，正常 ${res.data.normal}，异常 ${res.data.abnormal}，待人工 ${res.data.pending}`)
    await loadAuditUsers()
    if (currentAuditUser.value) {
      const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
      await loadAuditFiles(id)
    }
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}


async function adminAiReviewPending() {
  if (!confirmDanger('确定对所有未审查文件进行 AI/规则审查吗？')) return
  loading.value = true
  try {
    const res = await request('/admin/ai/review-pending', { method: 'POST' })
    setMessage(`未审查文件处理完成：共 ${res.data.total} 个，正常 ${res.data.normal}，异常 ${res.data.abnormal}，待人工 ${res.data.pending}`)
    await loadAuditUsers()
    if (currentAuditUser.value) {
      const id = auditBreadcrumb.value[auditBreadcrumb.value.length - 1]?.id ?? null
      await loadAuditFiles(id)
    }
    await loadAdminUsers()
  } catch (e) {
    setMessage(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (token.value) {
    try {
      const res = await request('/auth/me')
      user.value = res.data
      sessionStorage.setItem('cloudmind-user', JSON.stringify(user.value))
      if (!mustChangePassword.value) await loadFiles(null, true)
    } catch (e) {
      clearAuthState()
    }
  }
})

onBeforeUnmount(() => {
  clearThumbnailUrls()
  releasePreviewObjectUrl()
})
</script>

<template>
  <main class="app-shell" :class="{ 'page-drag-active': dragActive }" @dragenter.prevent="beginDragUpload" @dragover.prevent="keepDragUpload" @dragleave.prevent="leaveDragUpload" @drop.prevent="handleDrop">
    <section v-if="!user" class="login-screen">
      <div class="login-visual">
        <AppBrand class="brand-line" />
        <h1>智能云盘，让资料管理更像产品级工具</h1>
        <p>上传、检索、预览、相似推荐、AI 问答和管理员审查整合在同一个界面，适合作品展示和真实使用。</p>
        <div class="login-metrics">
          <span><b>AI</b> 摘要问答</span>
          <span><b>Admin</b> 后台审查</span>
          <span><b>Cloud</b> 文件管理</span>
        </div>
      </div>
      <form class="login-card" :aria-busy="loading" @submit.prevent="login">
        <div class="login-card-head">
          <span class="eyebrow">Welcome back</span>
          <h2>登录 CloudMind</h2>
          <p>请输入你的账号和密码。生产环境不会提供默认管理员凭据。</p>
        </div>
        <label for="login-username">用户名</label>
        <input id="login-username" v-model="username" autocomplete="username" placeholder="请输入用户名" required />
        <label for="login-password">密码</label>
        <input id="login-password" v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" required />
        <div class="login-actions">
          <button type="submit" :disabled="loading">{{ loading ? '正在登录…' : '进入云盘' }}</button>
          <button type="button" :disabled="loading" class="soft" @click="register">注册账号</button>
        </div>
        <p v-if="message" class="message login-message" :class="{ danger: loginMessageIsBanned }" :role="loginMessageIsBanned ? 'alert' : 'status'">{{ message }}</p>
      </form>
    </section>

    <section v-else-if="mustChangePassword" class="login-screen">
      <div class="login-visual">
        <AppBrand class="brand-line" />
        <h1>首次登录，请先设置新密码</h1>
        <p>管理员引导密码只用于首次部署。修改成功后，原登录令牌会立即失效。</p>
      </div>
      <form class="login-card" :aria-busy="loading" @submit.prevent="changePassword">
        <div class="login-card-head">
          <span class="eyebrow">Security check</span>
          <h2>修改初始密码</h2>
          <p>账号：{{ user.username }}</p>
        </div>
        <label for="current-password">当前密码</label>
        <input id="current-password" v-model="currentPassword" type="password" autocomplete="current-password" required />
        <label for="new-password">新密码</label>
        <input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" :minlength="isAdmin ? 14 : 6" maxlength="72" required />
        <label for="confirm-password">确认新密码</label>
        <input id="confirm-password" v-model="confirmPassword" type="password" autocomplete="new-password" :minlength="isAdmin ? 14 : 6" maxlength="72" required />
        <div class="login-actions">
          <button type="submit" :disabled="loading">{{ loading ? '正在修改…' : '保存新密码' }}</button>
          <button type="button" class="soft" :disabled="loading" @click="logout">退出登录</button>
        </div>
        <p v-if="message" class="message login-message" role="status">{{ message }}</p>
      </form>
    </section>

    <section v-else class="cloud-layout">
      <button v-if="sidebarOpen" class="sidebar-backdrop" aria-label="关闭导航" @click="sidebarOpen = false"></button>
      <aside class="sidebar" :class="{ admin: viewMode === 'admin', open: sidebarOpen }" :aria-label="viewMode === 'admin' ? '管理导航' : '主导航'">
        <div class="sidebar-brand">
          <AppBrand compact />
          <button class="mobile-nav-close" aria-label="关闭导航" @click="sidebarOpen = false"><X :size="20" /></button>
        </div>

        <nav v-if="viewMode !== 'admin'" class="side-nav" aria-label="文件与知识库" @click="sidebarOpen = false">
          <button :class="{ active: viewMode === 'files' || viewMode === 'search' }" @click="loadFiles(null, true)"><Folder :size="19" />全部文件</button>
          <button :class="{ active: viewMode === 'gallery' }" @click="loadGallery"><Images :size="19" />图片</button>
          <button @click="quickSearch('视频')"><Video :size="19" />视频</button>
          <button @click="quickSearch('文档')"><FileText :size="19" />文档</button>
          <button :class="{ active: viewMode === 'knowledge' }" @click="openKnowledge"><Sparkles :size="19" />AI 助手 <em>Beta</em></button>
          <button :class="{ active: viewMode === 'trash' }" @click="loadTrash"><Trash2 :size="19" />回收站</button>
          <button v-if="isAdmin" class="admin-entry" @click="openAdmin('dashboard')"><ShieldCheck :size="19" />管理员控制台</button>
        </nav>

        <nav v-else class="side-nav admin-nav" aria-label="管理员功能" @click="sidebarOpen = false">
          <button :class="{ active: adminTab === 'dashboard' }" @click="openAdmin('dashboard')"><Home :size="19" />控制台</button>
          <button :class="{ active: adminTab === 'users' }" @click="openAdmin('users')"><Users :size="19" />用户管理</button>
          <button :class="{ active: adminTab === 'audit' }" @click="openAdmin('audit')"><ClipboardCheck :size="19" />文件审核 <em>{{ adminAbnormalFiles }}</em></button>
          <button :class="{ active: adminTab === 'storage' }" @click="openAdmin('storage')"><HardDrive :size="19" />存储监控</button>
          <button :class="{ active: adminTab === 'ai' }" @click="openAdmin('ai')"><Bot :size="19" />AI 审核</button>
          <button @click="loadFiles(null, true)"><ArrowLeft :size="19" />返回网盘</button>
        </nav>

        <div class="side-storage-card" v-if="viewMode !== 'admin'">
          <div class="side-title"><b>存储空间</b><span>已用 {{ usedPercent }}%</span></div>
          <div class="thin-bar"><i :style="{ width: usedPercent + '%' }"></i></div>
          <p>{{ formatSize(usedBytes) }} / {{ formatSize(quotaBytes) }}</p>
          <button class="outline" @click="isAdmin ? openAdmin('storage') : setMessage('请联系管理员升级容量')">{{ isAdmin ? '管理存储空间' : '升级空间' }}</button>
        </div>


        <div class="side-storage-card admin-status" v-if="viewMode === 'admin'">
          <div class="side-title"><b>管理员提示</b><span>{{ storageOverview ? '在线' : '待刷新' }}</span></div>
          <p>{{ adminSystemStatus }}</p>
          <button class="outline" @click="openAdmin('storage')">查看详细状态</button>
        </div>
      </aside>

      <section id="main-content" class="workspace">
        <header class="topbar">
          <button class="mobile-nav-trigger" :aria-expanded="sidebarOpen" aria-label="打开导航" @click="sidebarOpen = true"><Menu :size="21" /></button>
          <div class="global-search" v-if="viewMode !== 'admin'">
            <Search :size="18" aria-hidden="true" />
            <input v-model="searchKeyword" aria-label="搜索文件、文件夹、标签或内容" placeholder="搜索文件、文件夹、标签或内容（/ 快捷键）" @keyup.enter="searchFiles" />
            <button @click="searchFiles">搜索</button>
          </div>
          <div v-else class="admin-titlebar">
            <h2>管理员控制台</h2>
            <p>系统运行总览与管理中心</p>
          </div>
          <div class="top-actions">
            <template v-if="viewMode !== 'admin'">
              <button class="primary top-action-button" :disabled="['trash', 'gallery', 'knowledge'].includes(viewMode)" @click="fileInput?.click()"><Upload :size="17" />上传文件</button>
              <button class="soft top-action-button secondary-upload" :disabled="['trash', 'gallery', 'knowledge'].includes(viewMode)" @click="folderInput?.click()"><FolderPlus :size="17" />上传文件夹</button>
              <button class="soft top-action-button" :disabled="['trash', 'gallery', 'knowledge'].includes(viewMode)" @click="createFolder"><FilePlus2 :size="17" />新建</button>
            </template>
            <template v-else>
              <button class="primary top-action-button" @click="openAdmin('ai')"><Bot :size="17" />AI 审核配置</button>
              <button class="soft" @click="loadFiles(null, true)">返回网盘</button>
            </template>
            <input ref="fileInput" type="file" :accept="ALLOWED_UPLOAD_ACCEPT" multiple hidden @change="uploadFiles" />
            <input ref="folderInput" type="file" :accept="ALLOWED_UPLOAD_ACCEPT" webkitdirectory multiple hidden @change="uploadFolder" />
            <div class="user-menu">
              <span class="avatar">{{ shortName(user.username) }}</span>
              <div><b>{{ user.username }}</b><small>{{ user.role }}</small></div>
              <button ref="sessionSecurityButton" class="icon-btn session-button" aria-label="管理设备会话" title="会话安全" @click="openSessionDialog"><ShieldCheck :size="17" /><span>安全</span></button>
              <button class="icon-btn logout-button" aria-label="退出登录" title="退出登录" @click="logout"><LogOut :size="17" /><span>退出</span></button>
            </div>
          </div>
        </header>

        <div v-if="dragActive && canDragUpload" class="drag-upload-overlay">
          <strong>松开鼠标开始上传</strong>
          <span>文件或文件夹会自动上传到当前目录：{{ currentFolderName }}</span>
        </div>

        <p v-if="message" class="message toast-message" role="status" aria-live="polite">{{ message }}</p>

        <template v-if="viewMode !== 'admin'">
          <section v-if="isRootFiles" class="home-grid">
            <div class="hero-card">
              <div>
                <span class="eyebrow">CloudMind Drive</span>
                <h1>上午好，{{ user.username }}</h1>
                <p>你的专属智能云盘，AI 帮你高效管理文件。</p>
                <div class="hero-actions">
                  <button @click="fileInput?.click()"><WandSparkles :size="17" />智能整理上传</button>
                  <button class="soft" @click="openKnowledge"><Sparkles :size="17" />文件摘要问答</button>
                </div>
              </div>
              <div class="cloud-illustration" aria-hidden="true">
                <Cloud :size="86" :stroke-width="1.25" />
                <FileText :size="34" :stroke-width="1.7" />
                <Sparkles :size="27" :stroke-width="1.7" />
              </div>
            </div>


            <div class="recent-card panel-card">
              <div class="panel-head"><h3>最近访问</h3><button class="link" @click="loadFiles(null, true)">全部 ›</button></div>
              <button v-for="item in recentActiveItems" :key="item.id" class="recent-item" @click="item.kind === 'FOLDER' ? openFolder(item) : previewFile(item)">
                <FileTypeIcon :item="item" :class="['file-icon', fileTypeClass(item)]" />
                <b>{{ item.name }}</b>
                <small>{{ fileTypeLabel(item) }} · {{ formatTime(item.updatedAt || item.createdAt) }}</small>
                <em v-if="item.summary">AI 摘要</em>
              </button>
              <div v-if="!recentActiveItems.length" class="empty compact">还没有文件，先上传一个吧。</div>
            </div>


            <div class="assistant-card panel-card">
              <div class="panel-head"><h3>智能助手</h3><button class="link" @click="openKnowledge">更多 ›</button></div>
              <article class="assistant-tip">
                <b>智能摘要</b>
                <p>基于你最近查看的文件，快速生成要点和参考来源。</p>
                <button class="outline" @click="openKnowledge">去问 AI</button>
              </article>
              <button class="task-row" @click="focusFile && previewFile(focusFile)">相似文件推荐 <span>{{ sideRelatedItems.length || '选择文件后生成' }} ›</span></button>
              <button class="task-row" @click="quickSearch('重复')">重复文件清理 <span>搜索重复 ›</span></button>
            </div>
          </section>

          <section v-if="viewMode === 'knowledge'" class="knowledge-page">
            <div class="knowledge-hero">
              <div>
                <span class="eyebrow">CloudMind Knowledge</span>
                <h2>AI 知识库问答</h2>
                <p>基于全部文件、指定文件或指定文件夹生成答案和概述；参考来源默认收起，不再占用大面积页面。</p>
              </div>
              <button class="soft" @click="loadFiles(null, true)">返回文件</button>
            </div>

            <div class="knowledge-layout">
              <section class="knowledge-chat">
                <div ref="knowledgeScrollRef" class="knowledge-scroll">
                  <div v-if="!knowledgeMessages.length" class="knowledge-empty">
                    <strong>你可以这样问：</strong>
                    <button @click="askPresetQuestion('帮我总结当前选择范围里的主要内容')">帮我总结当前选择范围里的主要内容</button>
                    <button @click="askPresetQuestion('这些资料里有哪些考试重点？')">这些资料里有哪些考试重点？</button>
                    <button @click="askPresetQuestion('根据我的资料，帮我整理一份复习提纲')">根据我的资料，帮我整理一份复习提纲</button>
                  </div>

                  <article v-for="(msg, index) in knowledgeMessages" :key="index" class="knowledge-message" :class="msg.role">
                    <div class="message-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
                    <div class="message-bubble">
                      <small v-if="msg.scopeLabel" class="scope-chip">{{ msg.scopeLabel }}</small>
                      <pre>{{ msg.content }}</pre>
                      <div v-if="msg.role === 'assistant'" class="answer-meta">
                        <mark :class="msg.usedAi ? 'ai-on' : 'ai-off'">{{ msg.usedAi ? 'AI生成' : '本地摘录' }}</mark>
                        <span v-if="msg.scope?.label">范围：{{ msg.scope.label }}</span>
                      </div>
                      <details v-if="msg.sources?.length" class="source-list compact-source-list">
                        <summary>参考来源（{{ msg.sources.length }}）</summary>
                        <button v-for="source in msg.sources" :key="source.id" @click="previewFile(source)">
                          <span class="source-name"><FileTypeIcon :item="source" :size="16" />{{ source.name }}</span>
                          <small>{{ source.snippet || source.summary || '相关资料' }}</small>
                        </button>
                      </details>
                      <div v-if="msg.suggestions?.length" class="suggestion-list compact-suggestions">
                        <button v-for="suggestion in msg.suggestions" :key="suggestion" @click="askPresetQuestion(suggestion)">{{ suggestion }}</button>
                      </div>
                    </div>
                  </article>
                  <p v-if="knowledgeLoading || knowledgeOverviewLoading" class="knowledge-thinking">正在检索知识库并生成内容...</p>
                </div>

                <div class="knowledge-input">
                  <textarea v-model="knowledgeQuestion" rows="3" placeholder="输入问题，例如：这份项目说明书的创新点是什么？" @keydown.ctrl.enter.prevent="askKnowledgeQuestion"></textarea>
                  <div class="knowledge-input-actions">
                    <button class="soft" :disabled="knowledgeLoading || knowledgeOverviewLoading || !knowledgeMessages.length" @click="clearKnowledgeChat">清空对话</button>
                    <button :disabled="knowledgeLoading || knowledgeOverviewLoading || !knowledgeQuestion.trim()" @click="askKnowledgeQuestion">发送问题</button>
                  </div>
                  <p class="tip">快捷键：Ctrl + Enter 发送。当前范围：{{ knowledgeScopeLabel }}。</p>
                </div>
              </section>

              <aside class="knowledge-source-panel">
                <div class="source-panel-head">
                  <h4>选择知识范围</h4>
                  <button class="link" @click="loadKnowledgeSources">刷新</button>
                </div>
                <p>选择全部文件、多个指定文件或某个文件夹后，问答和概述都会基于这个范围生成。</p>
                <div class="scope-tabs">
                  <button :class="{ active: knowledgeScopeType === 'ALL' }" @click="setKnowledgeScope('ALL')">全部文件</button>
                  <button :class="{ active: knowledgeScopeType === 'FILE' }" @click="setKnowledgeScope('FILE')">指定文件</button>
                  <button :class="{ active: knowledgeScopeType === 'FOLDER' }" @click="setKnowledgeScope('FOLDER')">指定文件夹</button>
                </div>
                <div class="scope-current">
                  <span>当前范围</span>
                  <strong>{{ knowledgeScopeLabel }}</strong>
                  <button v-if="knowledgeScopeType === 'FILE' && knowledgeScopeIds.length" class="mini-clear" @click="clearSelectedKnowledgeFiles">清空已选文件</button>
                </div>
                <template v-if="knowledgeScopeType !== 'ALL'">
                  <input v-model="knowledgeSourceKeyword" class="scope-search" placeholder="搜索文件名 / 文件夹名" />
                  <p v-if="knowledgeScopeType === 'FILE'" class="multi-select-tip">可连续点击选择多个文件，再统一问答或生成概述。</p>
                  <div class="source-picker-list">
                    <button v-for="item in filteredKnowledgeSources" :key="item.id" :class="{ selected: isKnowledgeSourceSelected(item) }" @click="selectKnowledgeSource(item)">
                      <span class="source-name"><FileTypeIcon :item="item" :size="17" />{{ item.name }}</span>
                      <small>{{ item.path }}</small>
                      <em v-if="item.kind === 'FOLDER'">{{ item.childKnowledgeCount || 0 }} 个可用资料</em>
                      <em v-else>{{ item.knowledgeReady ? '可用于问答' : '仅文件名可用' }}</em>
                    </button>
                    <div v-if="!filteredKnowledgeSources.length" class="source-picker-empty">没有找到匹配项</div>
                  </div>
                </template>
                <button class="overview-btn" :disabled="knowledgeSourcesLoading || knowledgeLoading || knowledgeOverviewLoading || !canRunKnowledgeOnScope" @click="generateKnowledgeOverview">
                  {{ knowledgeOverviewLoading ? '正在生成概述...' : '生成当前范围概述' }}
                </button>
              </aside>
            </div>
          </section>

          <section v-else-if="viewMode === 'gallery'" class="gallery-page">
            <div class="page-head"><div><span class="eyebrow">Album</span><h2>图片相册</h2></div><button class="soft" @click="loadFiles(null, true)">返回文件</button></div>
            <div class="gallery">
              <div v-if="!items.length" class="empty">还没有图片</div>
              <button v-for="item in items" :key="item.id" type="button" class="photo-card" @click="previewFile(item)">
                <img v-if="thumbnailUrls[item.id]" :src="thumbnailUrls[item.id]" :alt="item.name" />
                <FileTypeIcon v-else :item="item" :size="40" />
                <strong>{{ item.name }}</strong>
                <small>{{ formatDate(item.createdAt) }} · {{ formatSize(item.sizeBytes) }}</small>
              </button>
            </div>
          </section>

          <section v-else class="file-view-layout">
            <main class="file-main">
              <div class="folder-head" v-if="viewMode === 'files' && !isRootFiles">
                <nav class="breadcrumb">
                  <button class="crumb" @click="loadFiles(null, true)">全部文件</button>
                  <template v-if="viewMode === 'files'">
                    <button v-for="(b, index) in breadcrumb.slice(1)" :key="`${b.id}-${index}`" class="crumb" @click="goBreadcrumb(index + 1)">› {{ b.name }}</button>
                  </template>
                  <span v-else>› {{ modeTitle }}</span>
                </nav>
                <div class="folder-title-row">
                  <span class="big-folder-icon" aria-hidden="true"><Folder :size="30" /></span>
                  <div><h2>{{ currentFolderName }}</h2><p>{{ items.length }} 项 · 双击文件打开，单击文件查看相似内容</p></div>
                </div>
              </div>

              <div class="file-toolbar">
                <div>
                  <h3>{{ viewMode === 'trash' ? '回收站' : (viewMode === 'search' ? modeTitle : '全部文件') }}</h3>
                  <p>{{ items.length }} 个项目，已选 {{ selectedIds.length }} 项</p>
                </div>
                <div class="toolbar-actions">
                  <button class="soft view-toggle" :class="{ active: fileDisplayMode === 'list' }" aria-label="列表视图" title="列表视图" @click="setFileDisplayMode('list')"><List :size="18" /></button>
                  <button class="soft view-toggle" :class="{ active: fileDisplayMode === 'grid' }" aria-label="网格视图" title="网格视图" @click="setFileDisplayMode('grid')"><LayoutGrid :size="18" /></button>
                  <button v-if="viewMode !== 'trash'" class="soft" :disabled="!selectedIds.length" @click="openBatchTargetDialog('move')">批量移动</button>
                  <button v-if="viewMode !== 'trash'" class="soft" :disabled="!selectedIds.length" @click="openBatchTargetDialog('copy')">批量复制</button>
                  <button v-if="viewMode !== 'trash'" class="danger" :disabled="!selectedIds.length" @click="batchDeleteSelected">批量删除</button>
                </div>
              </div>

              <label v-if="items.length && viewMode !== 'trash'" class="select-all"><input type="checkbox" :checked="items.length > 0 && selectedIds.length === items.length" @change="toggleSelectAllVisible" /> 全选当前列表</label>

              <div v-if="fileDisplayMode === 'list'" class="file-table">
                <div class="file-row head"><span>文件名</span><span>摘要 / 标签</span><span>大小</span><span>修改时间</span><span>创建者</span><span></span></div>
                <div v-if="!items.length" class="empty">当前没有内容</div>
                <div v-for="item in items" :key="item.id" class="file-row" :class="{ focused: focusFile?.id === item.id }" @click="focusForRecommendation(item, $event)" @dblclick.stop="openItem(item)">
                  <span class="file-name-cell">
                    <input v-if="viewMode !== 'trash'" type="checkbox" :aria-label="`选择 ${item.name}`" :checked="isSelected(item)" @click="toggleSelected(item, $event)" @dblclick.stop />
                    <FileTypeIcon :item="item" :class="['file-icon', fileTypeClass(item)]" />
                    <button v-if="item.kind === 'FOLDER' && viewMode === 'files'" class="link file-name-link" @click.stop="openFolder(item)">{{ item.name }}</button>
                    <b v-else :title="item.name">{{ item.name }}</b>
                  </span>
                  <span class="summary-tags">
                    <small :title="item.summary || folderReviewTip(item)">{{ item.kind === 'FOLDER' ? folderReviewTip(item) : (item.summary || '暂无摘要') }}</small>
                    <em v-for="tag in tagList(item.tags)" :key="tag">{{ tag }}</em>
                  </span>
                  <span>{{ item.kind === 'FOLDER' ? '-' : formatSize(item.sizeBytes) }}</span>
                  <span>{{ formatTime(item.updatedAt || item.deletedAt) }}</span>
                  <span>{{ item.ownerName || item.createdBy || '我' }}</span>
                  <span class="row-actions">
                    <template v-if="viewMode === 'trash'">
                      <button class="soft" @click.stop="restoreItem(item)">恢复</button>
                      <button class="danger" @click.stop="purgeItem(item)">永久删除</button>
                    </template>
                    <template v-else>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="previewFile(item)">预览</button>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="downloadFile(item)">下载</button>
                      <button class="soft" @click.stop="openTargetDialog('move', item)">移动</button>
                      <button class="soft" @click.stop="openTargetDialog('copy', item)">复制</button>
                      <button class="soft" @click.stop="renameItem(item)">重命名</button>
                      <button class="danger" @click.stop="deleteItem(item)">删除</button>
                    </template>
                  </span>
                </div>
              </div>

              <div v-else class="drive-grid">
                <div v-if="!items.length" class="empty grid-empty">当前没有内容</div>
                <article v-for="item in items" :key="item.id" class="file-card" :class="{ focused: focusFile?.id === item.id }" @click="focusForRecommendation(item, $event)" @dblclick.stop="openItem(item)">
                  <div class="thumb" :class="fileTypeClass(item)">
                    <img v-if="canShowImageThumb(item) && thumbnailUrls[item.id]" :src="thumbnailUrls[item.id]" :alt="item.name" />
                    <FileTypeIcon v-else :item="item" :size="34" />
                    <input v-if="viewMode !== 'trash'" type="checkbox" :aria-label="`选择 ${item.name}`" :checked="isSelected(item)" @click="toggleSelected(item, $event)" @dblclick.stop />
                  </div>
                  <div class="file-card-body">
                    <strong>{{ item.name }}</strong>
                    <small>{{ fileTypeLabel(item) }} · {{ item.kind === 'FOLDER' ? '文件夹' : formatSize(item.sizeBytes) }}</small>
                    <p>{{ item.kind === 'FOLDER' ? folderReviewTip(item) : (item.summary || '暂无摘要') }}</p>
                    <div class="tag-line"><i v-for="tag in tagList(item.tags)" :key="tag">{{ tag }}</i><em v-if="!tagList(item.tags).length">暂无标签</em></div>
                  </div>
                  <div class="card-actions">
                    <template v-if="viewMode === 'trash'">
                      <button class="soft" @click.stop="restoreItem(item)">恢复</button>
                      <button class="danger" @click.stop="purgeItem(item)">永久删除</button>
                    </template>
                    <template v-else>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="previewFile(item)">预览</button>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="downloadFile(item)">下载</button>
                      <button class="soft" @click.stop="openTargetDialog('move', item)">移动</button>
                      <button class="danger" @click.stop="deleteItem(item)">删除</button>
                    </template>
                  </div>
                </article>
              </div>
            </main>

            <aside class="right-panel">
              <section class="recommend-card">
                <div class="panel-head"><h3>相似资料推荐</h3><button class="link" @click="clearRecommend">清空</button></div>
                <p v-if="!focusFile" class="muted">单击一个文件后，将展示相似文件、虚拟分组和 AI 摘要入口。</p>
                <template v-else>
                  <div class="focus-file">
                    <FileTypeIcon :item="focusFile" :class="['file-icon', fileTypeClass(focusFile)]" />
                    <div><b>{{ focusFile.name }}</b><small>{{ focusFile.summary || '暂无摘要' }}</small></div>
                  </div>
                  <p v-if="sideRecommendLoading" class="muted">正在生成推荐...</p>
                  <div v-for="group in recommendedGroups" :key="group.name" class="recommend-group">
                    <button class="group-title" @click="toggleRecommendGroup(group.name)"><span>{{ group.name }}</span><em>{{ group.files.length }} 个</em></button>
                    <div v-if="!isRecommendGroupClosed(group.name)">
                      <button v-for="r in group.files" :key="r.id" class="related-row" @click="previewRecommended(r)">
                        <FileTypeIcon :item="r" :class="['file-icon', fileTypeClass(r)]" />
                        <span><b>{{ r.name }}</b><small>{{ r.summary || '相似资料' }}</small></span>
                      </button>
                    </div>
                  </div>
                  <p v-if="!sideRecommendLoading && !recommendedGroups.length" class="muted">暂无相似资料。</p>
                </template>
              </section>

              <section class="ai-mini-card">
                <b>AI 助手</b>
                <p>基于当前文件，帮你快速提炼要点。</p>
                <button @click="openKnowledge">向 AI 提问</button>
              </section>
            </aside>
          </section>
        </template>

        <template v-else>
          <section v-if="adminTab === 'dashboard'" class="admin-dashboard">
            <div class="admin-kpi-grid">
              <article v-for="kpi in adminKpis" :key="kpi.label" class="admin-kpi" :class="{ danger: kpi.danger }">
                <span>{{ kpi.label }}</span>
                <b>{{ kpi.value }}</b>
                <small>{{ kpi.trend }}</small>
                <em><component :is="kpi.icon" :size="23" :stroke-width="1.8" /></em>
              </article>
            </div>

            <div class="admin-main-grid">
              <section class="admin-chart-card">
                <div class="panel-head"><h3>存储使用趋势</h3><button class="soft" @click="openAdmin('storage')">近况详情</button></div>
                <div class="line-chart-fake">
                  <i style="height: 42%"></i><i style="height: 52%"></i><i style="height: 58%"></i><i style="height: 61%"></i><i style="height: 66%"></i><i :style="{ height: Math.max(16, Number(storageOverview?.serverUsedPercent || 68)) + '%' }"></i>
                </div>
                <p>服务器已用 {{ formatSize(storageOverview?.serverUsedBytes || 0) }} / {{ formatSize(storageOverview?.serverTotalBytes || 0) }}</p>
              </section>

              <section class="admin-chart-card donut-card">
                <div class="panel-head"><h3>文件类型分布</h3></div>
                <div class="big-donut"><span>{{ adminTotalFiles.toLocaleString() }}<small>总文件数</small></span></div>
                <ul class="legend-list">
                  <li><i></i>文档 <b>42.1%</b></li><li><i></i>图片 <b>28.7%</b></li><li><i></i>视频 <b>14.3%</b></li><li><i></i>其他 <b>14.9%</b></li>
                </ul>
              </section>

              <section class="system-card">
                <h3>系统状态</h3>
                <p><span></span>系统状态 <b>{{ storageOverview?.statusText || '正常' }}</b></p>
                <p><span></span>存储服务 <b>正常</b></p>
                <p><span></span>AI 审核服务 <b>{{ aiConfig.enabled ? '已启用' : '规则模式' }}</b></p>
                <p><span></span>文件预览服务 <b>正常</b></p>
                <button class="link" @click="openAdmin('storage')">查看详细状态 →</button>
              </section>
            </div>

            <div class="admin-bottom-grid">
              <section class="admin-panel-card">
                <div class="panel-head"><h3>风险用户 / 最近审核</h3><button class="link" @click="openAdmin('audit')">查看全部 →</button></div>
                <div v-if="!adminTopRiskUsers.length" class="empty compact">当前没有异常用户。</div>
                <button v-for="u in adminTopRiskUsers" :key="u.id" class="risk-user-row" @click="openAdmin('audit').then(() => openAuditUser(u))">
                  <span class="avatar">{{ shortName(u.username) }}</span>
                  <b>{{ u.username }}</b>
                  <small>{{ u.enabled ? '账号正常' : '账号已封禁' }} · 异常 {{ u.abnormalCount || 0 }}</small>
                  <em :class="{ bad: !u.enabled, warning: Number(u.abnormalCount || 0) > 0 }">{{ !u.enabled ? '封禁' : '异常' }}</em>
                </button>
              </section>

              <section class="admin-panel-card pending-card">
                <div class="panel-head"><h3>常用管理操作</h3></div>
                <div class="admin-action-grid">
                  <button @click="adminAiReviewPending">一键 AI 审核未审查文件</button>
                  <button @click="adminAiReviewAll">一键全部 AI 审查</button>
                  <button @click="openAdmin('users')">重置用户密码</button>
                  <button @click="openAdmin('storage')">查看服务器容量</button>
                  <button @click="openAdmin('audit')">全站违规审查</button>
                  <button @click="openAdmin('ai')">配置 AI 接口</button>
                </div>
              </section>
            </div>
          </section>

          <section v-else-if="adminTab === 'users'" class="admin-section">
            <div class="section-head"><div><span class="eyebrow">User Management</span><h2>用户管理</h2><p>添加用户、封禁账号、重置密码、修改权限等级和 GB 容量。</p></div><button class="soft" @click="loadAdminUsers">刷新</button></div>
            <div class="create-user-card">
              <input v-model="adminCreateForm.username" aria-label="新用户用户名" placeholder="用户名" />
              <input v-model="adminCreateForm.password" aria-label="新用户初始密码" placeholder="初始密码" />
              <select v-model="adminCreateForm.role" aria-label="新用户权限等级"><option>USER</option><option>VIP</option><option>SVIP</option><option>ADMIN</option></select>
              <input v-model.number="adminCreateForm.quotaGb" type="number" min="0.1" step="0.1" aria-label="新用户容量（GB）" placeholder="容量 GB" />
              <button @click="createAdminUser">添加用户</button>
            </div>
            <div class="admin-table user-admin-table">
              <div class="admin-row head"><span>用户</span><span>状态</span><span>容量</span><span>权限</span><span>操作</span></div>
              <div v-for="u in adminUsers" :key="u.id" class="admin-row" :class="{ self: u.isSelf, banned: !u.enabled, abnormal: Number(u.abnormalCount || 0) > 0 }">
                <span><b>{{ u.username }}</b><small>ID {{ u.id }} · {{ formatTime(u.createdAt) }}</small></span>
                <span class="status-stack"><mark :class="accountStatusClass(u)">{{ accountStatusText(u) }}</mark><mark :class="contentStatusClass(u)">{{ contentStatusText(u) }} {{ u.abnormalCount || 0 }}</mark></span>
                <span><b>{{ formatSize(u.usedBytes) }} / {{ formatGbInputLabel(u.quotaBytes) }}</b><div class="thin-bar"><i :style="{ width: percentage(u.usedBytes, u.quotaBytes) + '%' }"></i></div></span>
                <span><mark class="role-badge">{{ u.role }}</mark></span>
                <span class="admin-actions">
                  <em v-if="u.isSelf" class="self-tip">当前管理员账号不可操作</em>
                  <template v-else>
                    <button class="soft" @click="openAdminUserEdit(u)">保存权限容量</button>
                    <button class="soft" @click="resetPassword(u)">重置密码</button>
                    <button :class="u.enabled ? 'danger' : 'soft'" @click="toggleUserEnabled(u)">{{ u.enabled ? '封禁' : '解封' }}</button>
                    <button class="danger" @click="deleteUser(u)">删除</button>
                  </template>
                </span>
              </div>
            </div>
          </section>

          <section v-else-if="adminTab === 'audit'" class="admin-section audit-section">
            <div class="section-head"><div><span class="eyebrow">Audit Center</span><h2>全站违规审查</h2><p>按用户浏览文件，进行预览、下载、手动标记和 AI 审查。</p></div><div class="head-actions"><button class="soft" @click="loadAuditUsers">刷新用户</button><button class="primary" @click="adminAiReviewPending">AI 审查未审查</button><button class="danger" @click="adminAiReviewAll">一键全部 AI 审查</button></div></div>
            <div class="audit-layout">
              <aside class="audit-users-card">
                <input v-model="auditUserKeyword" placeholder="搜索用户名" />
                <button v-for="u in filteredAuditUsers" :key="u.id" class="audit-user-node" :class="auditUserNodeClass(u)" @click="openAuditUser(u)">
                  <span class="avatar">{{ shortName(u.username) }}</span>
                  <b>{{ u.username }}</b>
                  <small>{{ accountStatusText(u) }} · 异常 {{ u.abnormalCount || 0 }}</small>
                </button>
              </aside>
              <main class="audit-files-card">
                <div class="audit-path" v-if="currentAuditUser">
                  <button v-for="(b, index) in auditBreadcrumb" :key="`${b.id}-${index}`" class="crumb" @click="goAuditBreadcrumb(index)">{{ index === 0 ? b.name : '› ' + b.name }}</button>
                </div>
                <div v-if="!currentAuditUser" class="empty">请先选择左侧用户。</div>
                <div v-else class="file-table audit-table">
                  <div class="file-row head"><span>文件</span><span>摘要 / 标签</span><span>状态</span><span>操作</span></div>
                  <div v-if="!auditItems.length" class="empty">该目录暂无文件。</div>
                  <div v-for="item in auditItems" :key="item.id" class="file-row audit-row" @dblclick.stop="item.kind === 'FOLDER' ? openAuditFolder(item) : previewFile(item, true)">
                    <span class="file-name-cell"><FileTypeIcon :item="item" :class="['file-icon', fileTypeClass(item)]" /><button v-if="item.kind === 'FOLDER'" class="link" @click.stop="openAuditFolder(item)">{{ item.name }}</button><b v-else>{{ item.name }}</b></span>
                    <span class="summary-tags"><small>{{ item.kind === 'FOLDER' ? folderReviewTip(item) : (item.summary || '暂无摘要') }}</small><em v-for="tag in tagList(item.tags)" :key="tag">{{ tag }}</em></span>
                    <span><mark :class="reviewClass(item.reviewStatus)">{{ reviewText(item.reviewStatus) }}</mark><small>{{ item.reviewNote || '' }}</small></span>
                    <span class="row-actions">
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="previewFile(item, true)">审查预览</button>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="downloadFile(item, true)">下载</button>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="adminAiReview(item)">AI 审查</button>
                      <button class="soft" v-if="item.kind === 'FILE'" @click.stop="markReview(item, 'NORMAL')">标记正常</button>
                      <button class="danger" v-if="item.kind === 'FILE'" @click.stop="markReview(item, 'ABNORMAL')">标记异常</button>
                    </span>
                  </div>
                </div>
              </main>
            </div>
          </section>

          <section v-else-if="adminTab === 'storage'" class="admin-section storage-section">
            <div class="section-head"><div><span class="eyebrow">Storage Monitor</span><h2>存储监控</h2><p>读取 Spring Boot 应用所在服务器磁盘容量，同时展示业务文件占用。</p></div><button class="soft" @click="loadAdminStorageOverview">刷新容量</button></div>
            <div v-if="!storageOverview" class="empty">正在读取存储信息...</div>
            <template v-else>
              <div class="storage-kpi-grid">
                <article class="storage-kpi"><span>服务器总容量</span><b>{{ formatSize(storageOverview.serverTotalBytes) }}</b><small>{{ storageOverview.serverDiskDescription || storageOverview.serverDiskName }}</small></article>
                <article class="storage-kpi warning"><span>服务器已用</span><b>{{ formatSize(storageOverview.serverUsedBytes) }}</b><small>占用 {{ formatPercent(storageOverview.serverUsedPercent) }}</small></article>
                <article class="storage-kpi success"><span>服务器可用</span><b>{{ formatSize(storageOverview.serverUsableBytes) }}</b><small>剩余 {{ formatPercent(storageOverview.serverUsablePercent) }}</small></article>
                <article class="storage-kpi"><span>全站文件实际占用</span><b>{{ formatSize(storageOverview.activeFileBytes) }}</b><small>{{ storageOverview.activeFileCount }} 个文件，{{ storageOverview.activeFolderCount }} 个文件夹</small></article>
              </div>
              <div class="storage-meter-card">
                <div class="meter-head"><div><b>服务器磁盘使用率</b><small>已用 {{ formatSize(storageOverview.serverUsedBytes) }} / 总 {{ formatSize(storageOverview.serverTotalBytes) }}</small></div><strong>{{ formatPercent(storageOverview.serverUsedPercent) }}</strong></div>
                <div class="bar storage-meter"><i class="used" :style="{ width: percentWidth(storageOverview.serverUsedPercent) }"></i></div>
              </div>
              <div class="disk-store-panel">
                <div class="meter-head"><div><b>磁盘分区明细</b><small>最后更新：{{ formatTime(storageOverview.updatedAt) }}</small></div></div>
                <div class="disk-table">
                  <div class="disk-row head"><span>磁盘</span><span>总容量</span><span>可用</span><span>使用率</span></div>
                  <div v-for="disk in storageOverview.diskStores || []" :key="disk.name" class="disk-row">
                    <span><b>{{ disk.primary ? '当前应用磁盘 · ' : '' }}{{ disk.description || disk.name }}</b><small>{{ disk.path || disk.name }}</small></span>
                    <span>{{ formatSize(disk.totalBytes) }}</span><span>{{ formatSize(disk.usableBytes) }}</span>
                    <span><strong>{{ formatPercent(disk.usedPercent) }}</strong><div class="bar tiny"><i class="used" :style="{ width: percentWidth(disk.usedPercent) }"></i></div></span>
                  </div>
                </div>
                <p class="tip">{{ storageOverview.note }}</p>
              </div>
            </template>
          </section>

          <section v-else-if="adminTab === 'ai'" class="admin-section ai-section">
            <div class="section-head"><div><span class="eyebrow">AI Config</span><h2>AI 模型 API 配置</h2><p>兼容 DeepSeek / OpenAI 风格接口；未配置 Key 时自动使用本地规则审查。</p></div><button class="soft" @click="loadAiConfig">刷新配置</button></div>
            <div class="ai-config-card">
              <label class="check-line"><input v-model="aiConfig.enabled" type="checkbox" /> 启用 AI 审查</label>
              <div class="ai-form">
                <input v-model="aiConfig.provider" placeholder="供应商名称，例如 DeepSeek / OpenAI 兼容" />
                <input v-model="aiConfig.baseUrl" placeholder="Base URL，例如 https://api.deepseek.com/v1" />
                <input v-model="aiConfig.model" placeholder="模型名，例如 deepseek-chat" />
                <input v-model="aiConfig.apiKey" type="password" :placeholder="aiConfig.hasApiKey ? '已保存 Key；不填则保留原 Key' : 'API Key'" />
              </div>
              <textarea v-model="aiConfig.reviewPrompt" rows="6" placeholder="审查提示词"></textarea>
              <div class="actions right">
                <button @click="saveAiConfig">保存 AI 配置</button>
                <button class="soft" @click="testAiConfig">测试连接</button>
                <button class="soft" @click="adminAiReviewPending">一键 AI 审查未审查</button>
                <button class="danger" @click="adminAiReviewAll">一键全部 AI 审查</button>
              </div>
              <p v-if="aiTestMessage" class="message inline">{{ aiTestMessage }}</p>
            </div>
          </section>
        </template>

        <div v-if="sessionDialog.visible" class="modal-mask" @click.self="closeSessionDialog">
          <section class="modal session-modal" role="dialog" aria-modal="true" aria-labelledby="session-dialog-title" aria-describedby="session-dialog-description" @keydown.esc.stop="closeSessionDialog">
            <header class="modal-head">
              <div>
                <span class="eyebrow">Account security</span>
                <h3 id="session-dialog-title">设备会话</h3>
                <p id="session-dialog-description">检查最近登录设备，发现陌生设备时立即下线。</p>
              </div>
              <button ref="sessionCloseButton" class="soft" aria-label="关闭设备会话" @click="closeSessionDialog"><X :size="17" />关闭</button>
            </header>
            <p v-if="sessionDialog.error" class="message inline danger" role="alert">{{ sessionDialog.error }}</p>
            <div v-if="sessionDialog.loading && !sessionDialog.items.length" class="session-empty" role="status"><LoaderCircle class="spin" :size="18" />正在读取会话…</div>
            <div v-else-if="!sessionDialog.items.length" class="session-empty">暂无有效设备会话。</div>
            <div v-else class="session-list" :aria-busy="sessionDialog.loading">
              <article v-for="session in sessionDialog.items" :key="session.id" class="session-row">
                <span class="session-icon"><ShieldCheck :size="20" /></span>
                <div class="session-info">
                  <div><b>{{ sessionDeviceName(session.userAgent) }}</b><em v-if="session.current">当前设备</em></div>
                  <p>IP：{{ session.clientIp || '未记录' }}</p>
                  <small>最近活动 {{ formatTime(session.lastUsedAt || session.createdAt) }} · 会话到期 {{ formatTime(session.expiresAt) }}</small>
                </div>
                <button class="soft danger-action" :disabled="session.current || sessionDialog.loading" @click="revokeDeviceSession(session)">
                  {{ session.current ? '正在使用' : '下线设备' }}
                </button>
              </article>
            </div>
            <p class="session-tip">修改密码或管理员调整你的权限后，所有旧会话都会自动失效。</p>
          </section>
        </div>

        <div v-if="previewVisible && previewData" class="modal-mask" @click.self="closePreview">
          <section class="modal large" role="dialog" aria-modal="true" aria-labelledby="preview-dialog-title">
            <header class="modal-head">
              <div><h3 id="preview-dialog-title">{{ adminPreviewMode ? '审查预览：' : '' }}{{ previewData.name }}</h3><p>{{ formatSize(previewData.sizeBytes) }} · {{ tagText(previewData.tags) }}<template v-if="adminPreviewMode"> · 状态：{{ reviewText(previewData.reviewStatus) }}</template></p></div>
              <div class="actions"><button v-if="adminPreviewMode" class="soft" @click="downloadFile(previewData, true)">无法预览时下载</button><button class="soft" aria-label="关闭预览" @click="closePreview"><X :size="17" />关闭</button></div>
            </header>
            <p class="summary-box">{{ previewData.summary || '暂无摘要' }}</p>
            <div v-if="previewData.previewType === 'VIDEO' || previewData.previewType === 'AUDIO'" class="media-tools"><span>播放倍速</span><select v-model.number="playbackRate" @change="applyPlaybackRate"><option :value="0.5">0.5x</option><option :value="1">1x</option><option :value="1.25">1.25x</option><option :value="1.5">1.5x</option><option :value="2">2x</option></select></div>
            <div class="preview-box">
              <img v-if="previewData.previewType === 'IMAGE'" :src="previewData.inlineUrl" alt="preview" />
              <video v-else-if="previewData.previewType === 'VIDEO'" ref="mediaRef" :src="previewData.inlineUrl" controls />
              <audio v-else-if="previewData.previewType === 'AUDIO'" ref="mediaRef" :src="previewData.inlineUrl" controls />
              <iframe v-else-if="previewData.previewType === 'PDF'" :src="previewData.inlineUrl"></iframe>
              <pre v-else-if="previewData.previewType === 'TEXT'">{{ previewData.text || '暂未提取到文本内容。' }}</pre>
              <div v-else class="empty">该文件类型暂不支持在线预览，可以点击下载查看。</div>
            </div>
            <div v-if="!adminPreviewMode" class="split-panels">
              <div><h4>相关资料推荐</h4><p v-if="!relatedItems.length" class="muted">暂无相关资料。</p><button v-for="r in relatedItems" :key="r.id" class="related" @click="previewFile(r)">{{ r.name }}<small>{{ r.summary }}</small></button></div>
              <div><h4>历史版本</h4><p v-if="!versions.length" class="muted">同名覆盖上传后会自动产生历史版本。</p><div v-for="v in versions" :key="v.id" class="version-row"><span>{{ formatTime(v.createdAt) }} · {{ formatSize(v.sizeBytes) }}</span><button class="soft" @click="restoreVersion(v)">恢复此版本</button></div></div>
            </div>
          </section>
        </div>

        <div v-if="adminEditDialog.visible" class="modal-mask" @click.self="adminEditDialog.visible = false">
          <section class="modal" role="dialog" aria-modal="true" aria-labelledby="edit-user-dialog-title">
            <h3 id="edit-user-dialog-title">修改权限等级与存储空间</h3>
            <p class="muted">用户：{{ adminEditDialog.user?.username }}。容量单位为 GB，支持小数，例如 0.5 表示 512MB。</p>
            <label for="edit-user-role">权限等级</label><select id="edit-user-role" v-model="adminEditDialog.role"><option>USER</option><option>VIP</option><option>SVIP</option><option>ADMIN</option></select>
            <label for="edit-user-quota">网盘总容量（GB）</label><input id="edit-user-quota" v-model.number="adminEditDialog.quotaGb" type="number" min="0.1" step="0.1" placeholder="例如 10" />
            <p class="tip">保存后，系统仍按“配额 + 配额 50% 临时缓冲”的规则判断上传。</p>
            <div class="actions right"><button class="soft" @click="adminEditDialog.visible = false">取消</button><button @click="confirmUpdateAdminUser">保存修改</button></div>
          </section>
        </div>

        <div v-if="targetDialog.visible" class="modal-mask" @click.self="targetDialog.visible = false">
          <section class="modal" role="dialog" aria-modal="true" aria-labelledby="target-dialog-title">
            <h3 id="target-dialog-title">{{ targetDialog.type === 'copy' ? '复制到' : '移动到' }}</h3>
            <p class="muted">目标：{{ targetDialog.items?.length > 1 ? `已选择 ${targetDialog.items.length} 个项目` : targetDialog.item?.name }}</p>
            <select v-model="targetDialog.targetParentId" aria-label="目标文件夹"><option :value="null">根目录</option><option v-for="folder in targetDialog.folders" :key="folder.id" :value="folder.id">{{ folder.name }}（ID: {{ folder.id }}）</option></select>
            <div class="actions right"><button class="soft" @click="targetDialog.visible = false">取消</button><button @click="confirmTargetAction">确定</button></div>
          </section>
        </div>

        <div v-if="loading" class="loading" role="status" aria-live="polite"><LoaderCircle class="spin" :size="18" />处理中…</div>
      </section>
    </section>
  </main>
</template>
