package top.hsyscn.opedrgent.tools.prompts

object EditToolPrompt {
    const val DESCRIPTION = "文件编辑：在本地文件系统中读取、写入或修改文件。"

    const val USAGE_GUIDELINES = """
## 文件操作工具使用规范

### 可用工具
- read_file: 读取文件内容
- write_file: 创建或覆盖文件
- edit_file: 修改文件特定部分
- list_directory: 列出目录内容

### 使用最佳实践
1. 先用 read_file 检查现有内容
2. 写入前确认文件路径正确
3. 大文件操作注意分块处理
4. 使用绝对路径而非相对路径

### 安全限制
- 禁止修改系统关键文件
- 禁止删除用户未确认的文件
"""

    fun getToolPrompt(): String = "$DESCRIPTION\n$USAGE_GUIDELINES"
}