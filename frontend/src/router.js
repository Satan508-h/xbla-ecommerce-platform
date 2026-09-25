import { createRouter, createWebHistory } from 'vue-router'

import ChatView from './views/ChatView.vue'
import ReportView from './views/ReportView.vue'
import StatusView from './views/StatusView.vue'

/**
 * 路由表。
 *
 * ## ★ 用 `createWebHistory`，不用 `createWebHashHistory`
 *
 * ```
 *   history 模式   https://域名/report        ← 用这个
 *   hash 模式      https://域名/#/report      ← 不用
 * ```
 *
 * ★ 代价是**服务端必须把所有路径都指回 `index.html`** ——
 * 用户直接访问 `/report`（或者在那页按 F5）时，那个路径在服务器上
 * 并不存在，Nginx 会回 404。所以 `deploy/nginx.conf` 里有：
 *
 * ```nginx
 *   location / { try_files $uri $uri/ /index.html; }
 * ```
 *
 * ⚠️ 这两处是一对：**改了路由模式就得改 Nginx，改了 Nginx 就得想想路由。**
 * 而配错一边的症状是「首页好好的，一刷新就 404」——
 * 看起来像后端挂了，其实是静态资源那一段没配对。
 *
 * ## ★ 为什么不用 hash 模式省掉这个麻烦
 *
 * 因为地址是要发给人看的。一个带 `#` 的地址在聊天软件里会被截断成
 * 纯文本链接的一部分，而 `/report` 不会。演示时要发地址给人，这一条够了。
 */
const routes = [
  { path: '/', name: 'chat', component: ChatView },
  { path: '/report', name: 'report', component: ReportView },
  { path: '/status', name: 'status', component: StatusView },
  // ★ 兜底：认不出的路径回首页，而不是留一个空白页
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
