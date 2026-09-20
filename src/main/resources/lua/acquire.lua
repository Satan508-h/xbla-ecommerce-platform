--[[
  获取名额 —— 阶段 6.1 / 6.2 / 6.3 的核心脚本。

  它一次原子地做完四件事，任何一步拆出去都会产生竞态：

    ① 清掉已过期的名额（僵尸）
    ② 有名额 → 直接占；没名额 → 进队列并返回位置
    ③ 幂等：已经持有名额的人再调一次，只续期、不会把自己排进队
    ④ 报一次「我还活着」

  ============================================================
  ★★ 为什么【不】在这里把名额分配给队首
  ============================================================

  直觉写法是「释放者原子地释放 + 提拔队首」，docs/02 §22 原来写的就是那个。
  但那条路有个洞：**队首可能已经断了**（关页面 / 超时 / 进程被杀）。
  提拔它 → 它永远不会来用这个名额 → 名额被幽灵占着直到 TTL 过期，
  期间后面所有人卡住 —— 这正是验收标准 1「无死锁」要测的东西。

  这里的形状是相反的：**分配只在这一个脚本里发生，由等待者自己来抢。**
  死人不参与竞争，所以「分给幽灵」从设计上不可能发生。
  用户选的是这条（2026-09-20）。

  ============================================================
  KEYS / ARGV
  ============================================================

    KEYS[1] slots     ZSet  成员=traceId  score=过期毫秒
    KEYS[2] queue     ZSet  成员=traceId  score=入队序号
    KEYS[3] alive     ZSet  成员=traceId  score=最后心跳毫秒
    KEYS[4] seq       String  INCR 计数器

    ARGV[1] traceId
    ARGV[2] nowMs          当前毫秒时间戳（★ 由 Java 传，不用 redis.call('TIME')）
    ARGV[3] ttlMs          名额租期
    ARGV[4] maxPermits     并发上限 N
    ARGV[5] maxQueue       队列长度上限

  返回值：{status, position, queueSize}
      status =  1  已拿到名额（position / queueSize 无意义，恒为 0）
      status =  0  在排队，position 是它前面还有几个人
      status = -1  队列也满了 —— 如实拒绝，不是静默丢弃

  ★ position 保持 ZRANK 的原生语义（0-based，第一个人前面是 0 个人），
    【不在这里 +1】。因为给用户看的那句话恰好就是「你前面还有 N 位」——
    那个 N 就是它本身。在这里 +1 会让第一个人收到「你前面还有 1 位」，
    而那种 off-by-one 看起来只像「有点慢」，不像 bug。

  ★ queueSize 只在 status = -1 时真正有用（「当前 N 人在等」比「请稍后」有用得多），
    但三个分支都返回它，是为了让返回值的【形状恒定】——
    形状随分支变化的返回值，调用方迟早会少读一个下标。

  ⚠️ ARGV[2] 的时间戳由 Java 传进来，而不是在脚本里调 redis.call('TIME')：
     Redis 的 TIME 返回的是 **Redis 服务器**的时钟。脚本里混用两个时钟源，
     会让「过期」这件事取决于两台的机器时间差，而那个差不会被任何人发现。
     同 docs/04 里「余额以数据库为准」的单一出处原则。

  ⚠️ Redis 的 Lua 用的是 **double** 存数字。毫秒时间戳约 1.7e12，
     小于 2^53 ≈ 9e15，加法不会丢精度 —— 但这是「刚好够用」，不是无限安全。
     真到需要 2^53 以上的那天，score 得换成秒。
--]]

-- ① 顺手清僵尸。
--    ★ 不能只靠定时任务：ZCARD 会把「已过期但还没被清掉」的名额算进去，
--      两次定时任务之间的窗口里会误判「满了」—— 而那个误判会让请求白白排队。
--      每次 acquire 都清一次，代价是一次 O(log N) 的范围删除。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])

-- ③ 幂等：已经持有名额的人再调一次，只续期。
--    ★ 没有这一段的话，一个持有者如果因为重试而再次走到 ②，
--      在「名额已满」时会被塞进队列 —— 于是同一个 traceId 同时
--      出现在 slots 和 queue 里。位置计算和清理都会跟着错。
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
    redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
    redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])
    return {1, 0, 0}
end

-- ② 有空位 → 直接占。
--    ZREM queue 是防御性的：正常路径不会走到（持有者不在队列里），
--    但「从队列被唤醒 → 抢到名额」这一步必须同时把它从队列里摘掉，
--    否则它会以一个幽灵的身份留在队列里，让后面所有人的位置偏大。
if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[4]) then
    redis.call('ZREM', KEYS[2], ARGV[1])
    redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
    redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])
    return {1, 0, 0}
end

-- ④ 满了 → 进队列。
--    ★★ 只有【不在队列里】才 ZADD —— 这是「保留原 score」的实现方式，
--       而保留原 score 是位置稳定的全部原因。
--       每次重试都 ZADD 一个新序号的话，被唤醒一次位置就往后跳一次，
--       用户会看到自己从「前面 2 人」变成「前面 7 人」。
if redis.call('ZSCORE', KEYS[2], ARGV[1]) == false then
    local waiting = redis.call('ZCARD', KEYS[2])
    if waiting >= tonumber(ARGV[5]) then
        return {-1, -1, waiting}
    end
    redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[4]), ARGV[1])
end

-- 排队中也要报平安：等待者的「重试」就是它的心跳，
-- 所以它掉线后不需要任何清理代码立刻生效 —— 不重试了，心跳就停了。
redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])

return {0, redis.call('ZRANK', KEYS[2], ARGV[1]), redis.call('ZCARD', KEYS[2])}
