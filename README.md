# 桃源 / TaoYuan

一个受中文网络小说《末日十日》(*Ten Days Till End*) 启发的 Minecraft Fabric 模组。

本模组是同人作品，与原作者及版权方无任何隶属或背书关系。

---

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.6+1.21.11` |
| Fabric Loom | `1.17-SNAPSHOT` |
| 映射 | `loom.officialMojangMappings()`（Mojang 官方映射） |
| Java | 21 |
| Mod ID | `taoyuan` |
| 许可 | GPL-3.0 |

构建：

```bash
./gradlew build
```

在开发环境启动客户端：

```bash
./gradlew runClient
```

---

## 目录

- [核心机制总览](#核心机制总览)
- [招灾（Calamity Bringer）](#招灾calamity-bringer)
- [恐惧分支的选择](#恐惧分支的选择)
- [七黑剑（Seven Sword）](#七黑剑seven-sword)
- [高空恐惧（Sky Terror）](#高空恐惧sky-terror)
- [强制处决管线](#强制处决管线)
- [持久化数据](#持久化数据)
- [开发辅助设施](#开发辅助设施)
- [代码结构](#代码结构)
- [尚未实现](#尚未实现)

---

## 核心机制总览

整条逻辑链只有一个起点：**招灾**这个状态效果。

```
玩家被施加 招灾 (taoyuan:calamity_bringer)
        │  招灾计数器 +1（终生累计，跨死亡、跨存档）
        ↓
每 30 秒降下一次「灾」
        │
        ├─ 计数器 < 10 ── 召唤阶段：身周刷 5~10 只怪，并强制锁定仇恨 10 秒
        │        │
        │        └─ 连续 2 次「刷不出任何怪」→ 计数器被直接推到 10，并立刻进入恐惧分支
        │
        └─ 计数器 ≥ 10 ── 后期阶段：波次调度器关停（正式内容尚未实现）
```

进入恐惧分支后，按玩家**周围的方块环境**二选一：

```
被埋在密闭空间里 ──→ 七黑剑（审判全服所有玩家）
        否则

---

## 招灾（Calamity Bringer）

> 注册 ID：`taoyuan:calamity_bringer`
> 实现：`effect/CalamityBringerEffect.java`、`effect/Calamity.java`

一个 `MobEffectCategory.HARMFUL` 状态效果。测试指令：

```
/effect give @p taoyuan:calamity_bringer
```

效果颜色沿用中毒的 `8889187` —— 因为 `MobEffect` 的两参数构造器会**从颜色反推环境粒子**，借用这个色值即可直接获得粒子表现。

### 招灾计数器

计数在 `onEffectAdded` 中 `+1`，而**不是** `onEffectStarted`。原因：`LivingEntity#addEffect` 每次施加都会调用后者（包括仅刷新已有效果），而前者只在效果原本不存在时触发。因此这个数字的语义是**「被施加招灾的次数」**，而不是「经历过的波数」。

计数器持久化、且 `copyOnDeath` —— 死亡与重登都不会清零。

### 波次时序

`shouldApplyEffectTickThisTick` 恒返回 `true`，即**每 tick 都执行**，因为仇恨锁定必须持续重设。

`applyEffectTick` 的判定顺序（顺序本身是设计的一部分）：

1. **和平难度**
   倒计时被「钉」在 30 秒满值，不推进、不召唤、不锁仇恨、不计失败。切回其他难度后享有完整 30 秒缓冲，而不是立刻挨一波。招灾计数器不受影响。

2. **后期阶段（计数器 ≥ 10）**
   倒计时被清为 `INDICATOR_OFF = 0`（且仅在非 0 时写一次，避免每 tick 写 attachment）。这个 `0` 同时是给 HUD 的信号，用来区分「已关闭」与「未激活」。

3. **倒计时 ≤ 0**
   **不降灾，而是重新装填 30 秒。** 这一分支兜住三种情况：后期阶段的计数器又被降下来了、老存档还没有这个 attachment、attachment 因其他原因丢失。它保证「重进世界」或「计数器变动」都不会在瞬间把一波怪砸在玩家头上。

4. **正常自减**，减到 0 时降灾，随后重置为 30 秒。

5. **仇恨锁定**
   `sinceWave = 1800 - countdown`，当 `sinceWave ≤ 200`（10 秒）时，每 tick 调用一次 `Calamity.lockAggression`。

`onEffectStarted`（每次施加/刷新都会跑）：后期阶段写 `0` 并返回；否则装满 30 秒，保证第一波在 30 秒后而非立即降临。

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `CALAMITY_INTERVAL_TICKS` | `30 * 20` | 降灾间隔 |
| `AGGRO_LOCK_TICKS` | `10 * 20` | 仇恨锁定持续 |
| `LATE_STAGE_THRESHOLD` | `10` | 进入后期阶段的计数器阈值 |
| `INDICATOR_OFF` | `0` | 「调度器已关停」的哨兵值 |
| `FAILED_WAVES_BEFORE_ESCALATION` | `2` | 强制升级所需的连续失败波数 |

### 召唤判定

候选点取自玩家脚下方块位置的**水平 5×5 环形**（半径 2），但**排除中心 3×3**（`SPAWN_EXCLUSION = 1`）—— 怪永远不会凭空出现在玩家身上。垂直方向从脚上方 `+2` 层扫到下方 `−8` 层，所以头齐高的岩架和脚下的地面一样可用。

每个候选点按介质分类（`Calamity.mediumAt`）：

- **先查流体、后查方块**：含水方块两者皆真，而「这里有水」是更有意义的事实。
- 用 `blocksMotion()` 而非 `isSolid()` —— 这与原版 `MOTION_BLOCKING` 高度图同源，且不会让火把、地毯被当成实心。

| 介质 | 附加要求 | 召唤内容 | 数量 |
| --- | --- | --- | --- |
| `WATER` | 该格与其上一格都是水（确保能完全没入） | **仅溺尸** | 5~10 |
| `LAVA` | 无 | 烈焰人 / 岩浆怪，均分 | 3~6，且每次放置只有 **50%** 概率真正生成 |
| `OPEN` | 必须通过 `isLandFootholdOk` | 僵尸 / 骷髅 / 蜘蛛 / 苦力怕，各 25% | 5~10 |

**为什么水里只有溺尸**：僵尸入水 300 tick 就会转化，而骷髅、蜘蛛、苦力怕会在氧气耗尽后憋死 —— 约 25 秒，**短于 30 秒的波间隔**，等于玩家什么都不用面对。溺尸能无限存续且会游泳，这才让「潜入水中」成为真正的风险而不是避难所。

**岩浆怪不限制体型**，交给原版 `Slime#finalizeSpawn` 自行随机（可能滚出 4 号体型、护甲 12）。这是刻意的：站在岩浆里本身就已致命，爬出来的是什么，是玩家自己的选择。

**介质优先级：水 > 岩浆 > 陆地。** 泡在水里就吃溺尸，不看旁边有没有干燥的岩架 —— 玩家选择了什么介质，就由那个介质来应对他。

#### 陆地落脚点三重条件（`isLandFootholdOk`）

1. `hasSubstantialFloor` —— 脚下必须有**连续 3 格**实心（`MIN_FLOOR_THICKNESS = 3`）；基岩视为无限厚。
2. `hasAdjacentFloor` —— 四个水平邻格中至少一个也满足上一条，即地面至少要有 **2×1** 的横向面积。
3. 陆地池中任一怪能通过原版 `SpawnPlacements.isSpawnPositionOk`。

**这三条的用意**：在天上搭一条一格宽（或三格厚以内）的浮桥不算「地面」。否则玩家只要铺一条走道，就会招来一波站在走道上的怪；而**成功的波次会清零失败计数**，高空恐惧便永远轮不到 —— 铺一条三格宽的路就能把「必死」悄悄降级成「几只杂鱼」。

#### 实际生成（`spawnAt`）

以 `EntitySpawnReason.MOB_SUMMONED` 创建，`snapTo` 到格心并随机朝向，然后：

- **流体内**放置用 `level.isUnobstructed(mob)`；
- **干地**放置用 `mob.checkSpawnObstruction(level)`。

区别的原因：`Mob#checkSpawnObstruction` 会因包围盒内含液体而一律拒绝 —— 而那恰恰是我们故意要做的放置。

#### 仇恨锁定（`lockAggression`）

把玩家包围盒 `inflate(8.0)`（约 16³ 的区域）内所有存活的 `Monster` 的 target 强制设为该玩家。每 tick 重设，这是仇恨不会飘到别的玩家身上的原因；调用一停，怪就恢复常规索敌。和平难度直接跳过。

### 失败与强制升级

- 只要本波**成功放下至少一只怪** → 失败计数清零。
- 否则失败计数 `+1`；达到 `2` 时：
  - 招灾计数器被**直接写成 `10`**，
  - 失败计数清零，
  - 若受害者是 `ServerPlayer`，**立即调用 `CalamityTerror.trigger`**。

这条规则存在的唯一目的：**堵死「把自己封进一格洞里，让任何东西都刷不出来」这条退路。** 躲起来不会让你避开机制，只会把下一阶段提前。

失败计数同样持久化 —— 升级不能通过「在两波之间重进世界」来规避。

---

## 恐惧分支的选择

> 实现：`effect/CalamityTerror.java`、`effect/Calamity.java`、`effect/CalamityRide.java`

`CalamityTerror.trigger` 是进入两种恐惧的**唯一入口**：

```java
enclosed = Calamity.isEnclosed(level, victim);   // 先判封闭
exposed  = Calamity.isExposedToSky(level, victim);

if (enclosed)      → 七黑剑
else if (exposed)  → 高空恐惧
else               → 什么都不发生（仅记日志）
```

三条设计原则：

- **完全不看高度，也不看维度。** 两种恐惧都纯粹由玩家**周围的方块**决定，所以 y=200 和 y=−50 判定相同，主世界、下界、末地也不需要各自的阈值。
- **封闭优先。** 两个条件可能同时成立（例如站在一条直通地表的竖井底部，既被围住、头顶又开阔），而「被埋起来」是二者中更确定的状态。
- **判定原点是 `CalamityRide.judgementPos(victim)`。** 骑乘时改用**载具**的方块坐标，而不是乘客脚下的坐标 —— 后者位于矿车/船的车体内部，会把铁轨读成「脚下的地面」、把船壳读成「实地」。矿车尤其严重：铁轨隧道的截面恰好就是封闭判定要找的形状。

### 封闭判定 `isEnclosedAt`（「墓穴判定」）

三条必须**同时**成立：

1. `origin.below()` 的介质是 `SOLID` —— 站在实地上，不是在游泳、也不是踩在流体上。
2. `origin.above(2)` 的介质是 `SOLID` —— 头顶有盖。
3. 在 **5×5×4** 的体积内（水平半径 2，高度 `TOMB_HEIGHT = 4`），除玩家自身占据的 2 格（`dx=0 && dz=0 && dy≤1`）以外，**非 `OPEN` 的格子 ≥ 34**（`TOMB_FILLED_CELLS`）。

可统计格子共 `5×5×4 − 2 = 98` 格，阈值 34 约合 35%。这是一个**密度判定**，不是「必须全部填实」—— 一块台阶或一扇活板门蒙不过去。

**流体计入「填满」**，所以被水淹的墓穴仍然是墓穴。

而地板与天花板必须是 `SOLID` 这一条，正是用来把**开阔水域**排除在外：游泳者四面被包围，但脚下没有地面、头上没有盖子；对他来说，身边刷出的一群溺尸已经是足够的回答了。

封闭判定**故意与 `summon` 相互独立** —— 一次波次失败可能出于跟「被埋」毫无关系的原因。

---

## 七黑剑（Seven Sword）

> 伤害类型：`taoyuan:seven_sword`
> 实现：`CalamityTerror.triggerSevenSwordTerror` / `runJudgment` / `judge`

### 出场

触发瞬间，触发者收到一段独白（`message.taoyuan.seven_sword`，斜体灰）：

> ……这里好黑……我从小就怕黑……黑暗里会不会有我自己幻想出来的七黑剑？被七黑剑裁定善之人赏纹银七两，被七黑剑裁定极善之人赏一锭金元宝，被七黑剑裁定大奸大恶之人被七黑剑贯穿丹田，可我此生从未做过善事，我会死的……

随后延迟 **3 秒**（`NARRATION_DELAY_TICKS = 3 * 20`）才真正落剑。延迟由 `CalamityTerror` 内建的调度器执行：`ServerTickEvents.END_SERVER_TICK` + 一个带 `synchronized` 保护的 `List<ScheduledTask>`。

### 审判范围：全服所有在线玩家

`runJudgment` 遍历 `level.getServer().getPlayerList().getPlayers()` —— **不只是触发者**。

每个玩家都在**自己所在的维度**（`p.level()`）结算，而不是触发者的维度。否则奖励会掉进坐标相同的错误世界，游戏规则与伤害事件也会对着玩家并不在的维度求值。

每人先听到两句交代因由的话：

> 我听到了招灾的回响。
> 招灾者召唤了七黑剑，七黑剑将审判所有人。

### 逐人裁定表

**顺序即优先级，命中即停止：**

| 顺位 | 条件 | 裁定 | 结果 |
| --- | --- | --- | --- |
| 1 | `getAbilities().invulnerable`（创造/无敌） | 极善 | 掉落 **1 个金锭**，提示「七黑剑无法审判神明」 |
| 2 | 身上带有 **招灾** 效果 | 大奸大恶 | **直接处决**，完全不看善恶账本 |
| 3 | `friendly_kills ≤ 10` | 极善 | 掉落 **1 个金锭**（「一锭金元宝」） |
| 4 | `friendly_kills ≤ 20` | 善 | 掉落 **7 个铁粒**（「纹银七两」） |
| 5 | `friendly_kills > 20` | 大奸大恶 | **直接处决** |

几点需要说明：

- **创造模式排在最前面**，因为一个 `abilities.invulnerable` 的玩家本来就杀不掉；把他排在「招灾即罪证」之前，路过的开发者或管理员会得到奖励而不是被反复击杀。
- **「携带招灾」本身就是罪证**。这些玩家是**以自己的名义**被斩，而不是作为触发者的连带伤害 —— 所以这一条排在善恶账本之前，善行等级对他们完全不适用。
- **`friendly_kills` 越高越坏。** 这个账本统计的是**杀害村民与铁傀儡**的次数，所以 `0~10 次 = 极善`、`11~20 次 = 善`、`>20 次 = 大奸大恶`。

### 善恶账本（`event/FriendlyKillTracker.java`）

- 挂在 `ServerLivingEntityEvents.AFTER_DEATH` 上。
- **只统计 `Villager` 与 `IronGolem`** —— 刻意排除动物与中立生物：这是一份道德账本，不是击杀计数器。
- 凶手取自 `source.getEntity()`，因此**宠物、弹射物造成的间接击杀同样归责**。
- 计数持久化且 `copyOnDeath`，跨登录、跨死亡、跨重载都不会清零。

### 裁定后的旁白（`narrateExecution`）

| 处决结果 | 播报 |
| --- | --- |
| `SAVED_BY_TOTEM` | 「我听到了替罪的回响。」+「一丝蕴含着替罪仙法的人偶替你扛下了七黑剑的审判」 |
| `SPARED_CREATIVE` | 「神明不会枉死。」 |
| `KILLED` | 不额外播报 —— 死亡消息本身已经说明了一切 |

死亡消息：

> `%s被七黑剑贯穿了丹田。`

---

## 高空恐惧（Sky Terror）

> 伤害类型：`taoyuan:sky_terror`
> 实现：`CalamityTerror.triggerSkyTerror`、`Calamity.isExposedToSkyAt`

### 出场

触发瞬间，触发者收到独白（`message.taoyuan.sky_terror`）：

> ……这里好高……这么高怎么办……小说主角也没有在这么高的地方存活下来的机会吧……我不会摔死吧……

同样延迟 **3 秒**后落下。与七黑剑不同，**高空恐惧只处决触发者一人**，不牵连他人、也不发放奖励 —— 高处没有可以裁定的善恶，只有掉下去。

死亡消息：

> `%s 在高空坠落，摔死在地面上。`

### 曝露判定 `isExposedToSkyAt`

三条必须**同时**成立：

```java
return !hasCeiling(level, origin)
    && isAirspaceClear(level, origin)
    && groundDepthBelow(level, origin) < GROUND_DEPTH_THRESHOLD;
```

#### 1. 头顶无遮蔽 `!hasCeiling`

从 `origin.y + AIRSPACE_HEIGHT`（即 `+3`）向上扫描：

| 维度类型 | 扫描上限 |
| --- | --- |
| 开阔维度（主世界、末地） | 一直扫到 `level.getMaxY()` —— 头顶**任何**方块都算遮蔽 |
| 有天花板的维度（下界） | 只扫 `CEILING_SCAN_LIMIT = 20` 格 |

下界之所以要限制：它整个被基岩顶封住，扫到世界顶端必然会撞上东西，高空恐惧在下界就永远不可能触发。限制扫描距离问的是**真正重要的那个问题** —— 玩家附近的上方有没有东西 —— 而不是「这个维度是不是封闭的」。

**刻意不使用 `canSeeSky`**：那个方法衡量的是**天空光照**，因此在下界恒为 `false`（`SKY_LIGHT_FACTOR` 为 0），并且会被树叶、玻璃、水削减。这里直接扫方块。

#### 2. 周围空域纯净 `isAirspaceClear`

**5×5×3** 的盒子（水平半径 `AIRSPACE_RADIUS = 2`，高度 `AIRSPACE_HEIGHT = 3`），除玩家自己站的那一格外，**全部必须是 `OPEN`** —— 一段栏杆、一面墙、一汪水出现在这个体积内，就说明玩家不是悬在空中。

盒子**从玩家脚下那一层开始**、而不是从下一层开始，这是整个条件能够成立的前提：任何站着的人脚下都有地板，把那一层算进去，条件将永远不可满足。三层描述的是「玩家占据的空间」加「他头顶紧邻的空间」。

#### 3. 脚下地层不够厚 `groundDepthBelow < 20`

从脚下逐格向下数**实心**格（流体与空气不计）：

| 常量 | 值 | 作用 |
| --- | --- | --- |
| `GROUND_DEPTH_THRESHOLD` | `20` | 少于 20 格实心地层即视为「悬空」 |
| `GROUND_GAP_TOLERANCE` | `3` | 允许跨越连续 ≤3 格的空洞后继续扫描 |
| `BEDROCK_DEPTH` | `100` | 扫到基岩直接按 100 计 |

- **允许跨越空洞**：天然地形到处是溶洞、含水层、峡谷；在第一个空洞就停下的扫描会把厚实的地面误判为薄薄一层岩架。每遇到空洞就延展这么多格，可以反复延展，只有**真正中空**的地面才会低于阈值。
- **但空洞过长就必须停止**：那时脚下确实什么都没有，再往下的地形不是玩家站着的东西。**这个区分就是整个计数器存在的意义** —— 如果放任扫描穿过任意深的虚空，它会在天空平台之下老远找到世界真正的地表，并把它算作玩家的地面，而那正是高空恐惧要抓的情形。
- **基岩短路**：站在世界底板上，绝不会被读成「悬在虚空之上」。
- 数到 20 就提前返回，因为调用方只跟阈值比较。

阈值定为 20 的理由：想一边移动一边维持这个厚度，意味着**每走一步都要铺二十格方块** —— 那本身就是答案。

### 与召唤阶段的配合

高空恐惧真正生效，靠的是[陆地落脚点三重条件](#陆地落脚点三重条件islandfootholdok)：浮桥不被承认为地面 → 召唤波次持续失败 → 连续 2 次后强制升级 → `trigger` → 判定曝露 → 处决。少了「地板厚度 ≥ 3 且至少 2×1」这条要求，玩家铺一条走道就能让波次成功，失败计数被清零，这条路径整个断掉。

---

## 强制处决管线

> 实现：`damage/TaoYuanExecution.java`、`damage/TaoYuanDeathContext.java`、`mixin/CombatTrackerMixin.java`

本模组落下的判决是**终局**。能救人的只有两样东西：**不死图腾**和**创造模式**。其余一切 —— `pvp` 游戏规则、队伍友伤设置、抗性提升、难度缩放、受击后的无敌帧 —— 全部**按设计被绕过**。

### 为什么不走 `hurtServer`

原版伤害管线至少有六处会独立地吞掉这一击：

| 位置 | 拒绝原因 |
| --- | --- |
| `ServerPlayer#hurtServer` | 攻击者是玩家且 `canHarmPlayer` 为假 |
| `Player#canHarmPlayer` | `pvp` 游戏规则关闭，或双方同队且禁止友伤 |
| `Player#hurtServer` | `abilities.invulnerable` 为真时直接拒绝 |
| 同上 | 和平难度把伤害归零（我们的伤害类型是 `scaling: always`） |
| `LivingEntity#getDamageAfterMagicAbsorb` | 抗性提升 V 把伤害减到 0 |
| `LivingEntity#hurtServer` | `invulnerableTime` 仍在倒数时整个丢弃 |

与其逐条对抗，`TaoYuanExecution` 直接跳过管线：`setHealth(0)` + `player.die(source)` —— 这正是原版 `LivingEntity#hurtServer` 最终调用的同一个终点。

### 执行顺序

1. **创造模式检查** → `SPARED_CREATIVE`（排在图腾之前，这样创造玩家不会为一场他本不受制的审判白白损失一个图腾）
2. **图腾检查** → `SAVED_BY_TOTEM`
3. `TaoYuanDeathContext.set(...)` → `setHealth(0)` → `die(source)` → `finally` 中 `clear`

### 图腾为何自行重实现（`consumeTotem`）

原版的 `LivingEntity#checkTotemDeathProtection` 是 `private`，且会对带有 `bypasses_invulnerability` 标签的伤害类型短路 —— 这个标签本模组日后可能会用，但不希望因此失去图腾支持。

重实现完整镜像了原版行为：遍历双手寻找 `DataComponents.DEATH_PROTECTION` → `shrink(1)` → 记录 `Stats.ITEM_USED` → 触发 `CriteriaTriggers.USED_TOTEM` → `causeUseVibration` → `setHealth(1)` → `applyEffects` → 广播实体事件字节 **35**（与原版相同）。

### 伤害源刻意不带攻击者

原版 `getLocalizedDeathMessage` 一旦发现造成伤害的实体手持改名物品，就会切换到 `.item` 翻译键，产出 `death.attack.taoyuan.seven_sword.item` 这样的缺失键。没有攻击者，消息就会停在原本的键上。

### 死亡消息的修补（`CombatTrackerMixin`）

绕过管线的代价是 `CombatTracker` 从未收到 `recordDamage`，于是 `getDeathMessage` 会走空记录分支、返回 `death.attack.generic`（显示成「XXX 死了」）。

补法：

```java
@Inject(method = "getDeathMessage", at = @At("HEAD"), cancellable = true)
```

若 `this.mob` 是 `ServerPlayer` 且 `TaoYuanDeathContext.current(player) != null`，就 `setReturnValue(source.getLocalizedDeathMessage(player))`。

注入到 `CombatTracker` 而非 `ServerPlayer#die` 的调用点，是为了把修补集中在一处：发给死者本人的数据包、广播给其他人的消息、以及队伍可见性的各种变体，查询的都是同一个 tracker。

**这个注入被刻意做得非常窄**：只有 `TaoYuanDeathContext` 中恰好存在该玩家的条目时才会命中，而那只在单次 `TaoYuanExecution#execute` 调用内部为真。其他任何死亡 —— 包括通过正常管线造成的本模组自有伤害类型 —— 一律不受影响。

`TaoYuanDeathContext` 用 `ConcurrentHashMap<UUID, DamageSource>`：按 UUID 而非实体索引，这样即使条目泄漏也绝不会持有 `ServerPlayer`；并发容器则是因为实体死亡可能跨维度由多个线程驱动。

### 伤害类型定义

`data/taoyuan/damage_type/seven_sword.json` 与 `sky_terror.json` 内容一致，仅 `message_id` 不同：

```json
{
  "message_id": "taoyuan.seven_sword",
  "scaling": "always",
  "exhaustion": 0.0,
  "effects": "hurt",
  "death_message_type": "default"
}
```

---

## 持久化数据

全部通过 Fabric 的 `AttachmentRegistry` 挂在实体上（`attachment/TaoYuanAttachments.java`）：

| Attachment | 持久化 | 死亡保留 | 同步 | 用途 |
| --- | --- | --- | --- | --- |
| `taoyuan:calamity_count` | ✔ | ✔ | 仅本人 | 招灾计数器 |
| `taoyuan:calamity_countdown` | ✔ | ✘ | 仅本人 | 下一次降灾的剩余 tick |
| `taoyuan:calamity_failed_waves` | ✔ | ✘ | ✘ | 连续失败波数 |
| `taoyuan:friendly_kills` | ✔ | ✔ | 仅本人 | 善恶账本（村民 / 铁傀儡击杀数） |

两处「持久化 / 不持久化」的取舍值得注意：

- **倒计时必须持久化。** 效果本身能跨重载存活，如果倒计时不能，它会归零并在世界加载的瞬间降下一波，把一群怪砸在毫无反应机会的玩家头上。
- **倒计时刻意不 `copyOnDeath`。** 重生应当开启一段全新的倒计时，而不是从周期中途接着走。

同步策略统一为 `AttachmentSyncPredicate.targetOnly()` —— 只发给该玩家自己，供 HUD 显示；客户端不依赖这些数据做任何别的事。

---

## 开发辅助设施

以下内容仅存在于开发构建，正式内容完成后应移除。

### 招灾指示器 HUD（`client/hud/CalamityIndicator.java`）

通过 `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ...)` 注册，绘制在右上角，加粗深红 `0xFFAA0000`，两行：

| 第一行状态 | 显示 |
| --- | --- |
| 计数器 ≥ 10 | `招灾指示器：已关闭[招灾计数器≥10次]` |
| 无招灾效果，或和平难度 | `招灾指示器：未激活` |
| 正常 | `招灾指示器：%s 秒`（`(countdown + 19) / 20` 向上取整，让它能停在「1」而不是在「0」上待满一秒） |

第二行始终显示 `招灾计数器：%s 次`。

指示器**无条件绘制**（不限于效果生效期间），因为它是开发辅助。要隐藏它，移除 `TaoYuanModClient` 中的注册即可。

### 终焉之花（`item/custom/TaoYuanFlowerItem.java`）

`taoyuan:taoyuan_flower`，加粗深红名称、深红斜体 lore：

> 很久很久以前...这里还叫做桃源...

调试交互：

- **右键** → 招灾计数器 `+1`
- **Shift + 右键** → 招灾计数器 `−1`（由 `Math.max(0, ...)` 夹住，误操作不会让它变成负数而搞乱下游逻辑）

变动结果通过 actionbar 提示 `招灾计数器：%d → %d`。

名称样式来自父类 `BloodTitleItem#getName` 而非 `ITEM_NAME` 组件 —— 因为 `Item` 构造器会调用 `buildAndValidateComponents(Component.translatable(descriptionId), ...)`，无条件地把 `ITEM_NAME` 覆盖成无样式组件，properties 上配置的样式会被丢弃。覆写 `getName(ItemStack)` 才是可靠路径，`ItemStack#getItemName` 会直接委托给它。

---

## 代码结构

```
src/main/java/com/taoyuan/
├── TaoYuanMod.java                    模组入口
├── attachment/TaoYuanAttachments.java 四个持久化 attachment
├── damage/
│   ├── TaoYuanDamageTypes.java        两个伤害类型的 ResourceKey
│   ├── TaoYuanDeathContext.java       跨 die() 传递死因
│   └── TaoYuanExecution.java          强制处决管线
├── effect/
│   ├── TaoYuanEffects.java            招灾效果注册
│   ├── CalamityBringerEffect.java     招灾：计数、时序、阶段判定
│   ├── Calamity.java                  召唤、仇恨锁、封闭 / 曝露判定
│   ├── CalamityTerror.java            恐惧分支、七黑剑审判、延迟调度器
│   └── CalamityRide.java              骑乘时的判定原点修正
├── event/FriendlyKillTracker.java     善恶账本
├── item/
│   ├── TaoYuanItems.java              物品注册
│   ├── TaoYuanItemGroups.java         创造模式物品栏
│   └── custom/
│       ├── BloodTitleItem.java        血红名称基类
│       └── TaoYuanFlowerItem.java     终焉之花（含调试交互）
└── mixin/
    ├── CombatTrackerMixin.java        修复强制处决的死亡消息
    └── TaoYuanMixin.java              模板残留，空实现

src/client/java/com/taoyuan/client/
├── TaoYuanModClient.java
├── hud/CalamityIndicator.java         招灾指示器
└── mixin/TaoYuanClientMixin.java      模板残留，空实现

src/main/resources/
├── fabric.mod.json
├── taoyuan.mixins.json
├── assets/taoyuan/                    材质、模型、语言（zh_cn / en_us）
└── data/taoyuan/damage_type/          seven_sword.json / sky_terror.json
```

---

## 尚未实现

- **`strikeLateStage`** —— 招灾计数器 ≥ 10 之后的常规波次内容仍是空的（只记录日志）。目前计数器 ≥ 10 的实际后果，只来自「连续 2 次召唤失败」那一次性的 `trigger` 调用。
- **`Calamity.SummonResult#anyCandidatesFound`** 已定义但暂无调用方；`strikeSummoning` 目前只判断 `spawned().isEmpty()`，因此「有落点但全部被拒绝」与「完全没有落点」在升级判定上等价。
- **`CalamityRide#isRiding` / `#isInMinecart`** 已定义但暂无调用方（`describeVehicle` 仅用于日志）。
- **非玩家生物**触发升级时，计数器会被写到 10，但不会进入恐惧分支；配合尚未实现的 `strikeLateStage`，该生物身上的招灾会彻底静默。
- **`TaoYuanMixin` / `TaoYuanClientMixin`** 是模板残留的空注入。

---

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。
