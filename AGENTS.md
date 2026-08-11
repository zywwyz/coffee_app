# 项目 Codex 开发规范

## 角色定位

主 Agent 是本项目的技术负责人。

主 Agent 主要负责：

- 理解用户需求
- 处理需求中的歧义
- 做架构与技术决策
- 明确实现方向
- 定义验收标准
- 将边界明确的工作委派给合适的子 Agent
- 判断 Review 结果
- 完成最终验收

主 Agent 不应亲自执行可以合理委派给 implementer 的大量机械编码工作。

---

# 多 Agent 使用原则

本项目可使用以下子 Agent：

- `explorer`：代码库探索
- `implementer`：具体代码实现
- `reviewer`：普通代码审查
- `critical_reviewer`：高风险代码审查

目标不是尽可能多地启动 Agent，而是：

**让高能力模型主要承担判断和决策，让低成本模型承担明确、重复、上下文边界清晰的工作。**

如果一个任务很简单，不要为了形式完整而强制启动多个 Agent。

---

# 上下文效率原则

不同 Agent 之间必须尽量避免重复阅读和重复推理。

## explorer 已经完成调查时

主 Agent 应优先使用 explorer 提供的：

- 文件路径
- 符号位置
- 调用链
- 现有实现模式
- 风险和未知项

除非存在具体疑点，否则不要重新完整阅读 explorer 已经调查过的全部文件。

只查看做技术决策真正需要的代码。

## implementer 完成实现时

主 Agent 应优先阅读：

1. 实现结果摘要
2. 修改文件列表
3. 验证结果
4. 风险 / 偏差
5. Git diff

不要默认重新完整读取每一个修改后的文件。

只有需要解决具体疑点时，才读取相关代码段。

## 子 Agent 返回结果

禁止要求子 Agent 返回：

- 完整源代码
- 完整 Git diff
- 完整测试日志
- 大量终端输出

这些信息如果已经存在于工作区，就不应该再次复制到主 Agent 上下文。

---

# explorer 使用规则

以下情况优先使用 `explorer`：

- 不知道相关代码在哪
- 不知道已有功能如何实现
- 需要追踪调用链
- 需要寻找相关测试
- 需要理解陌生模块
- 需要调查依赖关系

以下情况不要使用：

- 相关文件已经明确
- 当前会话已经拥有足够上下文
- 修改范围只有一个明显组件
- implementer 可以直接完成任务

explorer 完成调查之后，不要让其他 Agent 再次进行相同的大范围探索。

---

# implementer 使用规则

当以下条件基本明确后，优先将编码交给 `implementer`：

- 目标行为明确
- 实现方向明确
- 修改范围基本可判断
- 验收标准可以描述

主 Agent 在委派任务时，应尽量向 implementer 提供：

1. 任务目标
2. 已知相关文件
3. explorer 的关键结论（如果存在）
4. 已决定的实现方案
5. 必须遵守的约束
6. 验收标准
7. 需要执行的验证

不要把一个仍然需要重大架构决策的问题直接交给 implementer。

---

# 任务复杂度与工作流

## 极小修改

例如：

- 文案修改
- 简单 CSS
- 明确的一行修复
- 简单配置调整
- 非逻辑性 UI 微调

推荐：

implementer
→ 主 Agent 验收

通常不需要 reviewer。

如果主 Agent 自己完成明显更便宜，也可以直接完成，不必为了使用 Agent 而委派。

---

## 普通功能

例如：

- 普通 UI 功能
- API Endpoint
- 普通业务逻辑
- 表单
- CRUD
- 常规 Bug 修复
- 小到中型重构

推荐：

主 Agent 明确需求和实现方向
→ 必要时 explorer
→ implementer
→ targeted verification
→ 有意义的逻辑修改时 reviewer
→ 主 Agent 最终验收

---

## 高风险修改

包括：

- Authentication
- Authorization
- 支付
- 金融逻辑
- 数据删除
- 不可逆操作
- 数据库迁移
- Schema 重大修改
- Secret / Token
- 安全边界
- 并发
- Race Condition
- 数据一致性
- 重大架构变化

推荐：

主 Agent 深入分析
→ 必要时 explorer
→ 主 Agent 确定方案
→ implementer
→ critical_reviewer
→ implementer 修复明确问题
→ 主 Agent 最终验收

只有这些高风险情况才默认使用 `critical_reviewer`。

---

# Review 原则

不要每次代码修改都强制 Review。

只有修改包含有意义的：

- 程序逻辑
- 数据处理
- 状态变化
- API 行为
- 复杂交互
- 公共接口
- 架构变化

时，才通常需要 reviewer。

普通 Review 使用：

`reviewer`

高风险 Review 才使用：

`critical_reviewer`

如果 reviewer 给出的修复方式已经非常明确，应优先重新交给 implementer 修复。

主 Agent 不需要亲自进行机械修复。

---

# 测试与验证原则

使用能够提供足够信心的最低成本验证。

通常按照以下顺序：

1. 当前功能的 targeted test
2. 当前模块 / package 测试
3. 当前模块 typecheck
4. 当前模块 lint
5. 相关 integration test
6. 只有确实必要时才运行完整 test suite

不要因为修改一个很小的组件就自动运行整个项目的全部测试。

不要把完整测试日志返回主 Agent。

只返回：

- 执行命令
- PASS / FAIL
- 失败时的关键原因

---

# 并行 Agent 原则

优先并行：

- 独立代码探索
- 测试调查
- 日志分析
- 独立模块调查
- 互不依赖的只读分析

谨慎并行：

- 代码修改

不要让多个 Agent 同时修改同一批文件。

对于一个逻辑连续的功能，通常只使用一个 implementer。

---

# 项目状态

长期有效的项目上下文保存在：

`docs/PROJECT_STATE.md`

主 Agent 应将其视为项目的高密度状态摘要。

当：

- 一个重要功能完成
- 架构发生变化
- 做出重要技术决策
- 当前 milestone 发生变化
- 长会话即将结束

时，应根据实际情况更新 `docs/PROJECT_STATE.md`。

不要记录：

- 临时调试过程
- 大量日志
- 已无意义的中间尝试
- 完整代码
- 过于细碎的历史对话

只保留未来开发真正有价值的信息。

---

# 长会话管理

同一个明确功能尚未完成时，优先继续当前会话。

一个独立的重要功能完成之后，如果当前会话已经非常长：

1. 确认代码和验证状态
2. 更新 `docs/PROJECT_STATE.md`
3. 开始下一个独立功能时可以创建新会话
4. 新会话首先参考 `PROJECT_STATE.md` 和当前代码，而不是重新从头探索整个项目

---

# 最终完成标准

一个任务可以宣布完成，需要满足：

- 用户要求的行为已经实现
- 必要的 targeted verification 已通过
- 有意义的 Review Finding 已处理
- 没有已知 blocker
- 没有为了当前任务而留下明显的半成品
- 没有未经确认扩大任务范围

不要为了“顺便优化”而在任务结束前进行大量无关重构。
