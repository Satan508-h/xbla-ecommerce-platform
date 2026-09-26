<script setup>
/**
 * 顶层布局：一条导航 + 内容区。
 *
 * ## ★ 为什么导航里要放「评测报告」和「限流实况」这两页
 *
 * 因为这个项目最值得展示的不是「它能聊天」——那个十几行的 Naive RAG 也能做。
 * 值得展示的是**它是被测量过的**：
 *
 * ```
 *   聊天页     —— 功能
 *   评测报告   —— 阶段 7 的交付物本体（159 题 / 可复算 / 三个受控实验）
 *   限流实况   —— 阶段 6 的名额与队列（100 并发无超卖的那个装置）
 *   在线指标   —— 阶段 9.6b：真实流量 + 用户行为（★ 两组数分开摆）
 * ```
 *
 * ★ 换句话说，导航的这几项**就是简历上那几行**。
 * 面试官点开就能自己看，不用我在旁边讲。
 *
 * ⚠️ **「评测报告」和「在线指标」是两件事，别混**：
 * 前者是【离线】的（同一批题、同一配置，量检索与生成质量）；
 * 后者是【在线】的（这段时间里发生了什么）。两页的副标题都写着这句。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** ★ 用路由名算，不用路径字符串 —— 改路径时这里不会漏改 */
const activeNav = computed(() => route.name)
</script>

<template>
  <el-container class="app">
    <el-header class="app-header">
      <div class="brand">
        <span class="brand-name">休伯利安</span>
        <span class="brand-sub">电商导购与售后 · RAG 问答平台</span>
      </div>

      <!--
        ★ 用 el-menu 的 router 模式而不是自己写 <a>：
          它会跟着路由自动高亮，省掉一份「当前在哪个页面」的状态。
      -->
      <el-menu
        :default-active="activeNav"
        mode="horizontal"
        router
        class="app-nav"
      >
        <el-menu-item index="chat" :route="{ name: 'chat' }">问答</el-menu-item>
        <el-menu-item index="report" :route="{ name: 'report' }">评测报告</el-menu-item>
        <el-menu-item index="status" :route="{ name: 'status' }">限流实况</el-menu-item>
        <el-menu-item index="metrics" :route="{ name: 'metrics' }">在线指标</el-menu-item>
      </el-menu>
    </el-header>

    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app {
  height: 100%;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 32px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  /* ★ 导航不参与滚动：问答页的消息列表要自己滚，报告页要整页滚 */
  flex: none;
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex: none;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.brand-sub {
  font-size: 12px;
  color: #909399;
}

.app-nav {
  border-bottom: none;
  flex: 1;
}

/*
 * ★ app-main 要 min-height: 0。
 *   在 flex 布局里，子元素的默认 min-height 是 auto ——
 *   它拒绝收缩到比内容更小，于是内部那个「自己滚动」的容器
 *   永远算不出一个有限高度，滚动条就不出现，内容直接溢出。
 *   这个坑在「外层 flex + 内层 overflow: auto」时必然踩到。
 */
.app-main {
  padding: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
