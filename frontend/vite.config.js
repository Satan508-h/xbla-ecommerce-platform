import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createReadStream, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

/**
 * ★★ 允许通过 `/docs/` 访问的文件 —— **这是一份白名单，必须和 `deploy/nginx.conf` 里的一致**。
 *
 * ```
 *   11-评测报告.md      ✓ 阶段 7 的交付物本体，本来就要给人看
 *   11-附录-逐题.md     ✓ 同上
 *   00-项目总览.md      ✗✗✗ 【绝不能给】—— 那是简历故事线和面试讲稿，不含技术价值
 *   其他所有            ✗
 * ```
 *
 * ★★ 为什么这份清单要在两个地方各写一份、而不是「反正生产才是真的」：
 * 开发用的中间件如果比生产宽松，就会出现「本地能打开、部署上去 404」，
 * 而反过来（本地严、生产松）更糟 —— 那是**本地测不出来、上线才发现泄露**。
 * 两处用同一份清单，配错一边时症状是「两边行为不一致」，是最容易发现的形态。
 *
 * ★ 也因此，`docs/` 目录**整目录挂载**是不行的：Nginx 的挂载和 git 无关，
 * 而 `docs/00` 是靠 `.git/info/exclude` 不进公开仓库的 —— 它照样躺在磁盘上。
 */
const PUBLIC_DOCS = ['11-评测报告.md', '11-附录-逐题.md']

/**
 * 开发期把 `/docs/<白名单文件>` 直接映射到仓库的 `docs/` 目录。
 *
 * ## ★ 为什么不把报告复制进 `frontend/public/`
 *
 * 因为那样它就成了**一份快照** —— `python scripts/eval_report.py` 重跑之后，
 * 页面还是旧数字。而阶段 7 的整个价值就是「报告是可复算的」，
 * 复制一份会让「重跑报告」和「页面上的数字」之间多出一个需要记得做的同步步骤，
 * 而忘了做的时候，**没有任何迹象**。
 *
 * ## ★ 生产上是谁在提供这些文件
 *
 * 是 Nginx（`location /docs/ { alias /srv/public-docs/; }`），
 * 而那个目录里**只放这两个文件**（Dockerfile 里显式 COPY 两个，
 * 不是 COPY 整个 docs/）。所以这里和那里是同一套白名单的两个实现。
 *
 * ## ★★ 路径穿越：有两层，而它们返回的码【不一样】（2026-09-24 实测）
 *
 * ```
 *   /docs/00-项目总览.md               → 404   ← 本中间件的白名单
 *   /docs/../docs/00-项目总览.md       → 400   ← Vite 自己挡的，没到本中间件
 *   /docs/..%2fdocs%2f00-...md         → 400   ← 同上
 *   /docs/.hidden.md                   → 404   ← 本中间件的白名单
 * ```
 *
 * ★ 所以「白名单外一律 404」这句话**只对到达本中间件的请求成立**。
 * 带 `..` 的那几条在更前面就被拒了 —— 结论（拒绝）一样，
 * 但成因不同，写清楚才不会被下一个来查的人误读成「这个白名单挡不住穿越」。
 *
 * ★ 本中间件自己也有两道：白名单比对（`..` 不在清单里 → 404）、
 * 以及 `join` 之后的 `startsWith` 前缀校验（万一白名单被改错）。
 * 两道都在，只是实测走不到第二道。
 */
function publicDocsPlugin() {
  const repoDocs = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'docs')

  return {
    name: 'xbla-public-docs',
    configureServer(server) {
      server.middlewares.use('/docs', (req, res, next) => {
        // ⚠️ 先解码再比对：`%2e%2e%2f` 这类编码过的路径穿越
        //    不做这一步的话白名单形同虚设 —— 这是路径穿越最经典的入口
        let name
        try {
          name = decodeURIComponent((req.url || '').split('?')[0]).replace(/^\/+/, '')
        } catch {
          res.statusCode = 400
          return res.end('bad request')
        }

        if (!PUBLIC_DOCS.includes(name)) {
          // ★ 白名单外的一律 404。**不是 403** —— 403 等于承认「这个文件存在」，
          //   那本身就是一个信息泄露（可以用来枚举有哪些文档）
          res.statusCode = 404
          return res.end('not found')
        }

        const file = join(repoDocs, name)
        // ★ 双保险：join 之后必须仍在 repoDocs 里面
        if (!file.startsWith(repoDocs) || !existsSync(file)) {
          res.statusCode = 404
          return res.end('not found')
        }

        res.setHeader('Content-Type', 'text/markdown; charset=utf-8')
        createReadStream(file).pipe(res)
      })
    },
  }
}

/**
 * Vite 配置 —— 阶段 8。
 *
 * ## ★★ 这里最要紧的是那个 `proxy`
 *
 * 开发时前端在 5173 端口、后端在 8080 —— 浏览器会判定成**跨域**。
 *
 * 有三种解法，本项目选第三种：
 *
 * ```
 *   ① 后端配 CORS         ✗ 要改后端，而生产上又不需要（同源），
 *                          等于为了开发环境在生产代码里留一个口子
 *   ② 前端写死后端地址     ✗ 生产上是同源，写死会让构建产物绑死一个域名
 *   ③ Vite dev proxy      ✓ 浏览器以为自己在跟 5173 说话，
 *                          由 Vite 在【服务端】转发给 8080
 * ```
 *
 * ★ 第三种的好处是**浏览器那一侧根本没有跨域这回事** ——
 * 请求发往同源的 `/api/chat`，代理在 Node 侧发出去。
 * 所以后端一行 CORS 都不用写，生产用 Nginx 反代时也是同一套相对路径。
 *
 * ★ 换句话说：**开发和生产走的是同一个 URL 形状**（`/api/...`），
 * 只有「谁在转发」不同。这比「开发用 localhost:8080、生产用 /api」
 * 要少一个需要记住的区别。
 *
 * ## ⚠️ 这个 proxy 只服务开发
 *
 * `vite build` 的产物里没有代理 —— 生产靠 Nginx 的 `location /api/`。
 * 两边配错一边的症状是「本地好好的，部署上去 404」，
 * 所以 `docs/07` 的验收清单里有「在容器里点一次问答」。
 */
export default defineConfig({
  plugins: [vue(), publicDocsPlugin()],

  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // ★ 不重写路径：后端就是挂在 /api 下的，原样转发
      },
    },
  },

  build: {
    outDir: 'dist',
    // ★ 生产构建不生成 sourcemap：它会暴露源码结构，
    //   而这是个要挂到公网上的演示站
    sourcemap: false,
  },
})
