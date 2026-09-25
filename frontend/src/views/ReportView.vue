<script setup>
/**
 * 评测报告页 —— **这个项目最该被看见的一页**。
 *
 * ## ★★ 它显示的是「阶段 7 的交付物本体」，不是一份摘要
 *
 * 本项目的卖点是「检索质量可量化」，而这句话的可信度完全取决于
 * **别人能不能自己去核对**。所以这一页直接把 `docs/11-评测报告.md`
 * 渲染出来 —— 含噪声底、含分母、含「不能说的话」那一节。
 *
 * ```
 *   展示一个漂亮数字         → 说服力 ≈ 0（谁都能写一个）
 *   展示这个数字怎么来的      → 说服力全部在这里
 * ```
 *
 * ## ★★ 它是【运行时读文件】，不是构建时快照
 *
 * `python scripts/eval_report.py` 重跑之后，**刷新页面就是新数字**。
 * 把报告复制进 `frontend/public/` 会让它变成一份快照 ——
 * 而「快照和源文件不一致」这件事没有任何迹象，是最坏的那种不同步。
 *
 * ★ 生产上由 Nginx 提供这两个文件（`deploy/nginx.conf` 的 `/docs/`），
 * 开发期由 `vite.config.js` 里那个中间件提供 —— **两处用同一份白名单**。
 *
 * ## ⚠️ 白名单之外的文件【不能】出现
 *
 * 尤其是 `docs/00-项目总览.md`（简历故事线 + 面试讲稿）——
 * 它靠 `.git/info/exclude` 不进公开仓库，但**磁盘上有**，
 * 而 Nginx 的挂载和 git 无关。所以服务端是白名单而不是黑名单。
 */
import { computed, onMounted, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * ★ 可选的文件——**和后端白名单逐字一致**（`vite.config.js` 的 `PUBLIC_DOCS`
 * 与 `deploy/nginx.conf`）。这里多写一个，用户点了就是 404，
 * 而 404 在页面上看起来像「报告生成失败了」。
 */
const FILES = [
  { key: '11-评测报告.md', label: '评测报告' },
  { key: '11-附录-逐题.md', label: '附录 · 逐题' },
]

const active = ref(FILES[0].key)
const raw = ref('')
const loading = ref(false)
const error = ref('')

/**
 * ★★ 用 DOMPurify 洗一遍。
 *
 * 报告里**有模型生成的文本**（逐题明细里的回答、上下文片段）。
 * 而 `v-html` 会执行里面的 HTML —— 一段 `<img src=x onerror=...>`
 * 就是一个 XSS。虽然报告是我们自己的脚本生成的、输入来自我们自己的库，
 * 但「内容来源可信」是一条**会过期**的假设：今天不可信的东西，
 * 明天可能因为某个改动就变成可信的了。
 *
 * ★ 代价是一行代码和一个依赖；收益是这条假设不再需要成立。
 *   算得过来。
 */
const html = computed(() => {
  if (!raw.value) return ''
  return DOMPurify.sanitize(marked.parse(raw.value))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch(`/docs/${encodeURIComponent(active.value)}`, {
      cache: 'no-cache',
    })
    if (!res.ok) {
      throw new Error(
        res.status === 404
          ? '服务端没有提供这个文件（它可能还没生成，或者不在白名单里）'
          : `HTTP ${res.status}`,
      )
    }
    raw.value = await res.text()
  } catch (e) {
    raw.value = ''
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function switchTo(key) {
  active.value = key
  load()
}

onMounted(load)
</script>

<template>
  <div class="report">
    <div class="toolbar">
      <el-radio-group v-model="active" size="small" @change="switchTo">
        <el-radio-button v-for="f in FILES" :key="f.key" :value="f.key">
          {{ f.label }}
        </el-radio-button>
      </el-radio-group>

      <span class="path mono">/docs/{{ active }}</span>

      <div class="spacer" />

      <el-button size="small" :loading="loading" @click="load">重新读取</el-button>
    </div>

    <el-scrollbar class="body">
      <div class="inner">
        <el-alert v-if="error" type="warning" :closable="false" show-icon>
          <template #title>读不到报告</template>
          {{ error }}
        </el-alert>

        <el-skeleton v-else-if="loading" :rows="8" animated />

        <div v-else class="xbla-markdown" v-html="html" />
      </div>
    </el-scrollbar>
  </div>
</template>

<style scoped>
.report {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  flex: none;
}

.path {
  font-size: 12px;
  color: #909399;
}

.spacer {
  flex: 1;
}

.body {
  flex: 1;
  min-height: 0;
  background: #fff;
}

/*
 * ★ 报告正文限宽 + 居中。
 *   不限宽的话，一张宽表格会把行拉到 2000px，读起来要左右摇头。
 *   65em 差不多是 90 个汉字，是长文档比较舒服的行长。
 */
.inner {
  max-width: 65em;
  margin: 0 auto;
  padding: 24px 24px 60px;
}
</style>
