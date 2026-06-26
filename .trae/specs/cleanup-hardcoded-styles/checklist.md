# Checklist

- [x] `ui/` 包内无业务代码使用 `Color(0xFF...)` 或 `Color.Gray/White/Black`
- [x] `ui/components/` 包内无业务代码使用裸颜色
- [x] `fontSize = xx.sp` / `fontWeight` 组合仅在 `Type.kt` 出现
- [x] `Modifier.padding(x.dp)` 和 `RoundedCornerShape(x.dp)` 数量减少 80% 以上
- [x] 所有可交互 Icon 都有有意义的 `contentDescription`
- [x] 装饰性 Icon 的 `contentDescription = null` 带有解释注释
- [x] 强制深色模式下，聊天/笔记/编辑/发芽/面试/设置页无对比度崩溃
- [x] `./gradlew assembleDebug` BUILD SUCCESSFUL
