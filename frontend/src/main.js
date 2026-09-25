import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router.js'
import './style.css'

/**
 * 应用入口。
 *
 * ★ **不用 Pinia。** 这个应用只有三个页面、一份需要跨组件共享的状态
 * （当前会话号 + 消息列表），一个 `reactive` 的 composable 就够了。
 *
 * ```
 *   加 Pinia 的收益：跨页面共享状态更规范、有 devtools 时间旅行
 *   加 Pinia 的代价：多一个依赖、多一层「这个状态到底在哪个 store 里」的间接
 * ```
 *
 * ★ 判断标准是**状态的数量**：本项目需要共享的状态只有一处。
 * 等到出现第三、第四份共享状态时会话变了再引入，那时它解决的是真问题 ——
 * 而不是现在就为一个想象中的问题先架一层。
 *
 * ★ `zh-cn` locale 要显式给：Element Plus 的默认语言是英文，
 * 分页、日期、空状态那些组件会显示英文文案，和整站中文混在一起。
 */
createApp(App)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
