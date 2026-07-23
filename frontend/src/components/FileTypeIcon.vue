<script setup>
import { computed } from 'vue'
import {
  File,
  FileArchive,
  FileAudio,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileVideo,
  Folder,
  Presentation,
} from '@lucide/vue'

const props = defineProps({
  item: {
    type: Object,
    required: true,
  },
  size: {
    type: Number,
    default: 20,
  },
})

const type = computed(() => {
  if (props.item?.kind === 'FOLDER') return 'folder'
  const extension = String(props.item?.extension || props.item?.name?.split('.').pop() || '').toLowerCase()
  const mimeType = String(props.item?.mimeType || '').toLowerCase()
  if (mimeType.startsWith('image/') || ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(extension)) return 'image'
  if (mimeType.startsWith('video/') || ['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(extension)) return 'video'
  if (mimeType.startsWith('audio/') || ['mp3', 'wav', 'aac', 'flac', 'm4a'].includes(extension)) return 'audio'
  if (['xls', 'xlsx', 'csv'].includes(extension)) return 'sheet'
  if (['ppt', 'pptx'].includes(extension)) return 'slides'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(extension)) return 'archive'
  if (['pdf', 'doc', 'docx', 'txt', 'md', 'rtf'].includes(extension) || mimeType.startsWith('text/')) return 'document'
  return 'file'
})

const icon = computed(() => ({
  archive: FileArchive,
  audio: FileAudio,
  document: FileText,
  file: File,
  folder: Folder,
  image: FileImage,
  sheet: FileSpreadsheet,
  slides: Presentation,
  video: FileVideo,
})[type.value])
</script>

<template>
  <span class="file-type-icon" :class="`is-${type}`" aria-hidden="true">
    <component :is="icon" :size="size" :stroke-width="1.9" />
  </span>
</template>
