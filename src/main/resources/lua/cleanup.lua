--[[
  僵尸清扫 —— 阶段 6.6。

  两类「人没了」，两种判据：

    slots 里的僵尸   判据是【它自己的 score】—— score 就是过期时间，自描述。
                     一行 ZREMRANGEBYSCORE 就够，不依赖任何别的结构。
    queue 里的僵尸   判据是【alive】—— queue 的 score 是入队【序号】，不是时间，
                     它自己说不出「我是什么时候入的队」，所以必须问 alive。

  ★ 这就是为什么 queue 的 score 用序号而不是时间戳之后，仍然需要第三张表。
    「位置」和「存活」是两个正交的问题，硬塞进一个 score 里必然要牺牲一个。

  ============================================================
  KEYS / ARGV
  ============================================================

    KEYS[1] slots  KEYS[2] queue  KEYS[3] alive
    ARGV[1] nowMs          当前毫秒时间戳
    ARGV[2] aliveCutoffMs  心跳早于这个时刻的，判定为已死
    ARGV[3] batchSize      单次最多清理多少个排队僵尸

  返回值：{清扫掉的名额数, 清扫掉的排队僵尸数}

  ============================================================
  ★★ 为什么要 batchSize —— 这不是过度设计
  ============================================================

  docs/02 §22⑤ 自己写着：「脚本执行期间会阻塞 Redis，脚本写太长、循环太多，
  会卡住整个 Redis」。而 Redis 是单线程的 —— Lua 跑多久，**全站所有请求就等多久**。

  最坏情况是真的会发生的：一个人停止整个服务再重启（或者 Redis 抖了一下），
  几百个等待者同时停止心跳 → 下一次 cleanup 要删几百 × 2 个成员。
  ZREM 单个是 O(log N) 很快，但几百次串起来仍然是毫秒级，而这个毫秒级是
  【全世界都在等】的毫秒级。

  设了上限之后，剩下的僵尸会在下一次任务里被清掉 —— 反正它们已经死了，
  早 15 秒晚 15 秒没有区别。**「不阻塞 Redis」比「一次清干净」重要得多。**

  ★ 但是 slots 那一行【不设上限】：ZREMRANGEBYSCORE 是服务端原生操作，
    不经过 Lua 的逐元素循环，本来就是一次范围删除。它和上面的循环不是一回事。
--]]

-- ① 名额自带过期时间，自描述，不需要 alive 帮忙
local expiredPermits = redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])

-- ② 排队僵尸要靠心跳判断。
--    ★ LIMIT 的参数顺序是 (offset, count)，0 表示从头开始
local dead = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[2],
                        'LIMIT', 0, tonumber(ARGV[3]))

for i = 1, #dead do
    redis.call('ZREM', KEYS[3], dead[i])
    redis.call('ZREM', KEYS[2], dead[i])
end

return {expiredPermits, #dead}
