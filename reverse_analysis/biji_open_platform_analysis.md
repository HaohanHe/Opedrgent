# 得到大脑开放平台架构深度分析

## 一、开放平台概述

### 1.1 平台定位
> **一次安装，处处可用**
> 
> 将得到大脑能力接入各种AI平台，支持Claude Desktop、Claude Code、Cursor、Windsurf、Cline等主流工具

### 1.2 接入方式矩阵

| 接入方式 | 技术栈 | 适用场景 | 成熟度 |
|---------|--------|---------|--------|
| **MCP Server** | TypeScript/Node.js | AI模型直接调用 | ✅ 成熟 |
| **CLI 命令行** | Go | 终端/脚本自动化 | ✅ 成熟 |
| **OpenClaw Skill** | JSON配置文件 | 小龙虾生态 | ✅ 成熟 |
| **REST API** | HTTP/REST | 第三方集成 | ✅ 成熟 |
| **扣子Coze插件** | 插件格式 | 字节工作流 | 🚧 规划中 |
| **Dify集成** | 工作流 | 开源工作流 | 🚧 规划中 |

---

## 二、REST API架构

### 2.1 API基础信息
```
Base URL: https://openapi.biji.com/open/api/v1
认证方式: Bearer Token (API Key)
API Key获取: https://www.biji.com/openapi
```

### 2.2 API能力全景图

#### 笔记管理（Note Management）
```typescript
// 笔记CRUD操作
POST   /notes              // 创建笔记
GET    /notes              // 列表（游标分页）
GET    /notes/{id}         // 详情
PUT    /notes/{id}         // 更新
DELETE /notes/{id}         // 删除（回收站）

// 笔记类型支持
- plain_text: 纯文本 ✅
- link: 链接笔记（异步抓取） ✅
- img_text: 图片笔记 ✅
- audio: 即时录音 📖只读
- meeting: 会议录音 📖只读
- local_audio: 本地音频 📖只读
```

#### 语义搜索（Semantic Search）
```typescript
// 核心能力：AI驱动的语义召回
POST   /notes/recall              // 全局语义搜索
POST   /notes/recall/knowledge    // 知识库内搜索

// 参数
{
  "question": string,      // 搜索query
  "topic_ids": string[],  // 知识库ID列表
  "deep_seek": boolean,   // 启用深度思考
  "refs": boolean,         // 返回参考文献
  "history": []           // 对话历史
}
```

#### 知识库管理（Knowledge Base）
```typescript
// 知识库操作
GET    /topics                       // 列表
POST   /topics                       // 创建（限50/天）
GET    /topics/{id}/notes            // 笔记列表
POST   /topics/{id}/notes/batch      // 批量添加
DELETE /topics/{id}/notes/{note_id}  // 移除

// 订阅管理
GET    /topics/subscribed            // 我的订阅
POST   /topics/{id}/live-follow      // 订阅直播
```

#### 博主订阅（Blogger Subscription）
```typescript
// 博主内容订阅
GET    /topics/{id}/bloggers              // 博主列表
GET    /topics/{id}/bloggers/{id}/contents // 内容列表
GET    /bloggers/{id}/contents/{cid}       // 内容详情
```

#### 标签管理（Tag Management）
```typescript
POST   /notes/{id}/tags      // 添加标签
DELETE /notes/{id}/tags/{tag}// 删除标签
```

#### 媒体上传（Media Upload）
```typescript
// 图片上传流程（3步）
1. GET /upload/token          // 获取上传凭证
2. POST to OSS endpoint       // 上传到阿里云OSS
3. POST /notes                // 创建图片笔记

// 支持格式
- mime_type: png/jpg/gif等
- 最大尺寸: 10MB
- 存储: ali-bj2-oss-get-notes-prod
```

---

## 三、MCP Server实现架构

### 3.1 项目信息
```yaml
名称: getnote-mcp
语言: TypeScript (57.9%) + JavaScript (42.1%)
包名: @getnote/mcp
安装: npm install -g @getnote/mcp 或 npx @getnote/mcp
Star: ~500+
维护: iswalle (活跃维护)
```

### 3.2 MCP工具清单（26个工具）

#### 笔记管理（8个）
```typescript
✅ list_notes      // 游标分页列表
✅ get_note        // 详情（支持原图）
✅ save_note       // 创建（文本/链接/图片）
✅ update_note     // 更新（仅plain_text）
✅ delete_note     // 删除到回收站
✅ get_note_task_progress  // 异步任务进度
✅ share_note      // 生成分享链接
```

#### 语义搜索（2个）
```typescript
✅ recall              // 全局语义召回
✅ recall_knowledge   // 知识库语义召回
```

#### 知识库（6个）
```typescript
✅ list_topics              // 知识库列表
✅ create_topic             // 创建知识库
✅ list_topic_notes         // 知识库笔记
✅ batch_add_notes_to_topic // 批量添加
✅ remove_note_from_topic   // 移除笔记
✅ list_subscribe_topics    // 订阅列表
```

#### 博主订阅（3个）
```typescript
✅ list_topic_bloggers          // 博主列表
✅ list_topic_blogger_contents  // 内容列表
✅ get_blogger_content_detail  // 内容详情
```

#### 直播订阅（2个）
```typescript
✅ list_topic_lives    // 直播列表
✅ get_live_detail     // 直播详情（含AI摘要）
```

#### 标签（2个）
```typescript
✅ add_note_tags     // 添加标签
✅ delete_note_tag   // 删除标签
```

#### 媒体（2个）
```typescript
✅ get_upload_config  // 获取上传配置
✅ get_upload_token    // 获取OSS凭证
✅ upload_image       // 完整上传（自动获取凭证+上传）
```

#### 其他（1个）
```typescript
✅ get_quota          // API配额查询
```

### 3.3 核心代码结构
```
src/
├── index.ts        // MCP Server入口
├── client.ts       // API客户端
├── config.ts       // 配置管理
├── logger.ts       // 日志系统
├── rate-limiter.ts // 限流器（2 QPS, 5000/天）
└── types.ts        // 类型定义
```

### 3.4 限流机制
```typescript
// 智能限流
- QPS: 2请求/秒
- 每日: 5000请求/天
- 重置: 北京时间00:00
- 策略: 超出返回429错误
```

---

## 四、CLI命令行工具实现

### 4.1 项目信息
```yaml
名称: @getnote/cli
语言: Go (94.5%) + JavaScript (4.0%)
安装: npm install -g @getnote/cli
平台: macOS/Linux/Windows
版本: v1.1.8 (36个版本)
```

### 4.2 CLI命令树

```
getnote
├── auth
│   ├── login [--api-key --client-id]
│   ├── status
│   └── logout
├── save <url|text|image>
│   ├── [--title]
│   └── [--tag]
├── notes [--limit --all -o json]
├── note <id>
│   ├── [--field]
│   ├── update [--title --content --tag]
│   ├── delete [-y]
│   └── share
├── search <keyword> [--limit --kb]
├── tag
│   ├── add <id> <tag>
│   ├── remove <id> <tag>
│   └── list <id>
├── kb
│   ├── [topic_id] [--limit --all]
│   ├── create [--desc]
│   ├── add <topic_id> <note_id>...
│   ├── remove <topic_id> <note_id>...
│   ├── bloggers
│   └── live-follow <url>
└── kbs-sub
```

### 4.3 核心设计模式

#### 1. OAuth登录
```bash
getnote auth login
# 自动打开浏览器完成授权
```

#### 2. API Key登录
```bash
getnote auth login --api-key gk_live_xxx --client-id cli_xxx
```

#### 3. 异步任务处理
```bash
getnote save https://example.com
# 自动轮询，返回最终笔记
# -o json 模式下静默轮询
```

#### 4. 结构化输出
```bash
getnote notes -o json
getnote search "keyword" -o json
# AI可以直接解析的JSON格式
```

---

## 五、OpenClaw Skill实现

### 5.1 Skill配置文件
```json
{
  "name": "getnote",
  "version": "1.8.0",
  "description": "让AI成为你的第二大脑",
  "capabilities": [
    "保存链接/图片/文字",
    "语义搜索",
    "知识库管理",
    "博主订阅",
    "直播订阅"
  ]
}
```

### 5.2 使用场景模式

#### 随手记录模式
```
用户: 记一下笔记：支付流程可以加进度条
AI: 已记录，自动打上「产品优化」标签
```

#### 语义召回模式
```
用户: 帮我找找这周工作相关的东西
AI: 找到5条相关笔记：周一客户反馈...周三技术方案...
```

#### 链接保存模式
```
用户: 存到笔记 https://example.com/article
AI: 链接已提交，正在抓取分析中...
    搞定 ✓ 已保存：《文章标题》
```

### 5.3 安全机制
```json
{
  "GETNOTE_OWNER_ID": "ou_xxx"  // 配置后只有你能操作
}
// 群聊中其他人无法读取你的笔记
```

---

## 六、关键技术亮点

### 6.1 异步任务处理
```typescript
// 链接笔记创建流程
1. POST /notes { link_url: "..." }
2. 返回 { task_id: "xxx", status: "processing" }
3. 轮询 GET /notes/tasks/{task_id}
4. status: "success" 后返回最终笔记

// CLI实现
while (task.status === 'processing') {
  await sleep(1000);
  task = await getTaskProgress(task_id);
}
```

### 6.2 OSS图片上传
```typescript
// 三步流程
1. 获取凭证: GET /upload/token
   返回: { accessid, host, policy, signature, object_key }

2. 上传到OSS: POST { host }
   Headers: multipart/form-data
   Fields: OSSAccessKeyId, policy, Signature, key, file

3. 创建笔记: POST /notes
   使用凭证中的 access_url
```

### 6.3 语义搜索增强
```typescript
// recall接口参数
{
  question: string,           // 搜索query
  topic_ids?: string[],       // 限定知识库
  deep_seek?: boolean,        // 深度思考模式
  refs?: boolean,             // 返回引用
  history?: ChatMessage[],    // 对话历史
  top_k?: number,             // 返回数量
  intent_rewrite?: boolean,   // 意图重写
  select_matrix?: boolean     // 结果重选
}
```

### 6.4 限流与配额
```typescript
// 智能限流器
class RateLimiter {
  private qps = 2;
  private daily = 5000;
  
  async acquire(): Promise<void> {
    // 令牌桶算法
  }
  
  async checkQuota(): Promise<QuotaInfo> {
    // GET /quota
  }
}
```

---

## 七、生态集成案例

### 7.1 Claude Desktop配置
```json
{
  "mcpServers": {
    "getnote": {
      "command": "npx",
      "args": ["-y", "@getnote/mcp"],
      "env": {
        "GETNOTE_API_KEY": "gk_live_xxx",
        "GETNOTE_CLIENT_ID": "cli_xxx"
      }
    }
  }
}
```

### 7.2 Claude Code Skill
```bash
npx skills add iswalle/getnote-cli -y -g
# 安装后可以用自然语言操作
```

### 7.3 OpenClaw一键安装
```bash
clawhub install getnote
# 支持飞书、微信、Telegram等渠道
```

### 7.4 扣子Coze插件
```
已上线: https://www.coze.cn/store/plugin/7636708949818900499
```

---

## 八、与我们项目的对比

### 8.1 架构对比

| 维度 | 得到大脑 | 我们的项目(opedrgent) | 差距 |
|------|---------|---------------------|------|
| **MCP支持** | ✅ 完整实现 | ❌ 未实现 | 🔴 |
| **REST API** | ✅ 完整实现 | ⚠️ 内部API | 🟡 |
| **CLI工具** | ✅ Go实现 | ❌ 无 | 🔴 |
| **Skill系统** | ✅ 内置+第三方 | ⚠️ 内置Skill | 🟡 |
| **发芽引擎** | ✅ 四大场景 | ⚠️ 四阶段流程 | 🟢 |
| **语义搜索** | ✅ recall接口 | ❌ 无 | 🔴 |
| **知识库** | ✅ 完整实现 | ❌ 无 | 🔴 |

### 8.2 技术对比

| 能力 | 得到大脑 | 我们的项目 | 借鉴点 |
|------|---------|-----------|--------|
| **语音转文字** | ASR + 27种方言 | Sherpa-ONNX | ✅ 相似 |
| **多模态输入** | 语音/图片/链接/文字 | 音频/视频 | ✅ 相似 |
| **发芽机制** | 四大场景 | 四阶段流程 | ✅ 相似 |
| **Agent模式** | Skill系统 | 工具系统 | 🔄 可升级 |
| **记忆系统** | 全量笔记召回 | 无 | 🔴 需实现 |
| **知识库** | 订阅+语义搜索 | 无 | 🔴 需实现 |

---

## 九、我们可以借鉴的设计

### 9.1 立即可借鉴（高优先级）

#### 1. **MCP Server模式**
```
建议: 实现我们的MCP Server
- 支持Claude/Cursor等AI工具调用opedrgent能力
- 暴露工具: sprout(发芽), stt(语音转文字), search(记忆搜索)
```

#### 2. **语义搜索召回**
```
建议: 实现recall接口
- 利用已有Embedding能力
- 支持全局召回和知识库召回
- 添加deep_seek深度思考模式
```

#### 3. **Skill配置文件**
```json
{
  "name": "opedrgent",
  "capabilities": ["sprout", "stt", "memory", "research"],
  "triggers": ["发芽", "生发", "分析", "联想"]
}
```

### 9.2 中期规划（1-2月）

#### 1. **CLI工具**
```
建议: 开发Go CLI
- getrgent save <audio|text>
- getrgent sprout <note_id>
- getrgent search <keyword>
```

#### 2. **笔记内链系统**
```
建议: 实现笔记引用
- 格式: opedrgent://note/{id}
- 支持发芽结果之间的关联
- 知识图谱构建
```

#### 3. **异步任务处理**
```
建议: 实现任务队列
- 视频转音频任务
- 语音识别任务
- 发芽分析任务
```

### 9.3 长期规划（3-6月）

#### 1. **知识库订阅**
```
建议: 支持外部内容订阅
- 播客RSS订阅
- 博主内容追踪
- 直播自动摘要
```

#### 2. **多Agent协作**
```
建议: 参考"小步"助手
- 多个专业Agent协作
- 主Agent协调调度
- 专业领域Agent: 发芽/点评/拷问/打磨
```

---

## 十、总结：得到大脑的成功秘诀

### 10.1 技术层面

1. **开放生态**：MCP + CLI + REST API + Skill，多层次接入
2. **AI原生**：从一开始就为AI设计，不是后期改造
3. **异步优先**：耗时操作队列化，用户体验流畅
4. **语义优先**：不是关键词搜索，是语义召回

### 10.2 产品层面

1. **极简交互**：自然语言 > UI操作
2. **场景聚焦**：记录/召回/发芽，核心场景清晰
3. **渐进增强**：免费基础 → 会员高级 → 专家专业
4. **生态联动**：App + Web + 硬件 + 第三方平台

### 10.3 商业层面

1. **API开放**：吸引开发者共建生态
2. **会员订阅**：核心AI能力付费解锁
3. **硬件入口**：录音卡作为流量抓手
4. **平台整合**：扣子/Dify/Claude，无处不在

---

## 十一、下一步行动建议

### 立即实施（本周）
1. ✅ 已完成：发芽作为Skill（已完成）
2. 🔄 进行中：完善Skill触发机制
3. 📋 计划：实现MCP Server基础框架

### 短期规划（1月）
1. 实现语义搜索召回（recall接口）
2. 开发发芽引擎的MCP工具暴露
3. 添加异步任务处理队列

### 中期规划（2-3月）
1. 开发CLI工具（Go实现）
2. 实现笔记内链系统
3. 添加知识库基础功能

### 长期愿景（6月+）
1. 多Agent协作架构
2. 知识库订阅系统
3. 开放API生态

---

**分析日期**: 2026年6月3日
**数据来源**: 
- https://www.biji.com/openapi (官方开放平台)
- https://github.com/iswalle/getnote-mcp (MCP Server源码)
- https://github.com/iswalle/getnote-cli (CLI源码)
- https://github.com/iswalle/getnote-openclaw (OpenClaw Skill)
- doc.biji.com (用户文档)
