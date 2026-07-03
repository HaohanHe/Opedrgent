package top.hsyscn.opedrgent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Patterns
import android.widget.Toast
import kotlinx.coroutines.withContext
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult
import top.hsyscn.opedrgent.utils.DebugLog
import org.json.JSONObject

/**
 * run_intent 工具 — 对标 Google Gallery 的 Native Intent 执行机制。
 *
 * 允许 LLM 通过标准化接口调用 Android 原生能力，如发送邮件、短信、
 * 创建日历事件等。这是 Gallery Skill 系统中 "Native Skill" 的执行路径。
 *
 * ## 支持的 Intent 类型
 * - **send_email**: 发送邮件（调用系统邮件应用）
 * - **send_text_message**: 发送短信（调用系统短信应用）
 * - **create_calendar_event**: 创建日历事件
 * - **open_url**: 在浏览器中打开 URL
 * - **share_text**: 分享文本到其他应用
 * - **dial_phone**: 拨打电话
 *
 * @param context Android Context
 * @param requestConfirmation 高危操作前的用户确认回调
 */
class RunIntentTool(
    private val context: Context,
    private val requestConfirmation: suspend (ToolConfirmation) -> Boolean = { true },
) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        "run_intent" to ToolBinding(
            name = "run_intent",
            description = "调用 Android 原生 Intent 执行设备操作。支持：send_email（发邮件）、send_text_message（发短信）、create_calendar_event（创建日历事件）、open_url（打开链接）、share_text（分享文本）、dial_phone（拨号）。参数为 JSON 字符串，包含 intent 类型和各操作所需的具体参数。",
            invoker = { toolPart, _, _, _ -> execute(toolPart) },
        ),
    )

    /**
     * 执行 Native Intent。
     *
     * 从 toolPart.state.input 解析参数：
     * - intent: Intent 类型名称
     * - parameters: JSON 字符串格式的参数
     */
    private suspend fun execute(toolPart: ToolPart): ToolResult {
        val input = toolPart.state.input
        DebugLog.i("RunIntentTool: 执行 Native Intent — input=${input.toString().take(200)}")

        return try {
            val args = JSONObject(input)
            val intentType = args.optString("intent", "")
            val paramsStr = args.optString("parameters", "{}")
            val params = JSONObject(paramsStr)

            if (intentType.isBlank()) {
                return emptyResult(toolPart, "缺少 intent 参数，请指定要执行的操作类型")
            }

            val confirmation = buildConfirmation(intentType, params)
            val confirmed = requestConfirmation(confirmation)
            if (!confirmed) {
                return emptyResult(toolPart, "用户拒绝了 $intentType 操作")
            }

            val result = dispatchIntent(intentType, params)

            ToolResult(
                toolPart = toolPart.copy(
                    state = toolPart.state.copy(
                        status = ToolStateType.COMPLETED,
                        output = result,
                        endTime = System.currentTimeMillis(),
                    ),
                ),
            )
        } catch (e: Exception) {
            DebugLog.e("RunIntentTool 异常: ${e.message}", e)
            emptyResult(toolPart, "Intent 执行异常: ${e.message}")
        }
    }

    /**
     * 根据 intent 类型分发到具体的 Android Intent 实现。
     */
    private fun dispatchIntent(intentType: String, params: JSONObject): String {
        return when (intentType) {
            "send_email" -> handleSendEmail(params)
            "send_text_message" -> handleSendTextMessage(params)
            "create_calendar_event" -> handleCreateCalendarEvent(params)
            "open_url" -> handleOpenUrl(params)
            "share_text" -> handleShareText(params)
            "dial_phone" -> handleDialPhone(params)
            else -> "[失败] 不支持的 Intent 类型: $intentType。支持的类型: send_email, send_text_message, create_calendar_event, open_url, share_text, dial_phone"
        }
    }

    // ==================== 确认与校验辅助 ====================

    private fun buildConfirmation(intentType: String, params: JSONObject): ToolConfirmation {
        val detail = when (intentType) {
            "send_email" -> "收件人: ${params.optString("extra_email", "")}\n主题: ${params.optString("extra_subject", "")}"
            "send_text_message" -> "号码: ${params.optString("phone", "")}\n内容: ${params.optString("text", "").take(200)}"
            "create_calendar_event" -> "标题: ${params.optString("title", "")}\n地点: ${params.optString("location", "")}"
            "open_url" -> "URL: ${params.optString("url", "")}"
            "share_text" -> "内容: ${params.optString("text", "").take(200)}"
            "dial_phone" -> "号码: ${params.optString("phone", "")}"
            else -> "Intent 类型: $intentType"
        }
        return ToolConfirmation(
            toolName = "run_intent",
            action = "执行设备操作: $intentType",
            detail = detail,
        )
    }

    private fun isPhoneNumber(phone: String): Boolean {
        return phone.matches(Regex("^[+0-9\\-\\s()]{3,20}$"))
    }

    private fun isUrlSafe(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            scheme == "http" || scheme == "https"
        } catch (_: Exception) {
            false
        }
    }

    // ==================== 各 Intent 处理器 ====================

    /** 发送邮件 — 调用系统邮件应用 */
    private fun handleSendEmail(params: JSONObject): String {
        val email = params.optString("extra_email", "")
        val subject = params.optString("extra_subject", "")
        val text = params.optString("extra_text", "")

        if (email.isBlank()) return "[失败] 发送邮件失败：缺少收件人地址 (extra_email)"
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return "[失败] 发送邮件失败：邮箱格式不正确"
        }

        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            }

            val chooser = Intent.createChooser(intent, "发送邮件")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            "[成功] 已打开邮件编辑器 → 收件人: $email${if (subject.isNotBlank()) ", 主题: $subject" else ""}"
        } catch (e: Exception) {
            "[失败] 发送邮件失败: ${e.message}。请确认设备已安装邮件应用。"
        }
    }

    /** 发送短信 — 调用系统短信应用 */
    private fun handleSendTextMessage(params: JSONObject): String {
        val phone = params.optString("phone", "")
        val text = params.optString("text", "")

        if (phone.isBlank()) return "[失败] 发送短信失败：缺少手机号码 (phone)"
        if (!isPhoneNumber(phone)) return "[失败] 发送短信失败：手机号码格式不正确"

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                if (text.isNotBlank()) putExtra("sms_body", text)
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            "[成功] 已打开短信编辑器 → 收件人: $phone"
        } catch (e: Exception) {
            "[失败] 发送短信失败: ${e.message}"
        }
    }

    /** 创建日历事件 — 调用系统日历应用 */
    private fun handleCreateCalendarEvent(params: JSONObject): String {
        val title = params.optString("title", "")
        val location = params.optString("location", "")
        val description = params.optString("description", "")

        // 解析时间参数（支持时间戳或 ISO 格式）
        val startEpochMs = params.optLong("start_time", 0L)
            .takeIf { it > 0 } ?: System.currentTimeMillis()
        val endEpochMs = params.optLong("end_time", 0L)
            .takeIf { it > 0 } ?: startEpochMs + 3600_000L // 默认1小时

        return try {
            val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title.ifBlank { "新事件" })
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endEpochMs)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                .putExtra(CalendarContract.Events.DESCRIPTION, description)

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                "[成功] 已打开日历事件编辑器 → 「$title」"
            } else {
                "[警告] 未找到日历应用，无法创建事件"
            }
        } catch (e: Exception) {
            "[失败] 创建日历事件失败: ${e.message}"
        }
    }

    /** 在浏览器中打开 URL */
    private fun handleOpenUrl(params: JSONObject): String {
        val url = params.optString("url", "")

        if (url.isBlank()) return "[失败] 打开链接失败：缺少 URL 参数"
        if (!isUrlSafe(url)) return "[失败] 打开链接失败：仅支持 http/https 协议"

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                "[成功] 已在浏览器中打开: $url"
            } else {
                "[警告] 没有可用的浏览器应用"
            }
        } catch (e: Exception) {
            "[失败] 打开链接失败: ${e.message}"
        }
    }

    /** 分享文本到其他应用 */
    private fun handleShareText(params: JSONObject): String {
        val text = params.optString("text", "")
        val title = params.optString("title", "分享")

        if (text.isBlank()) return "[失败] 分享失败：缺少文本内容"

        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
            }

            val chooser = Intent.createChooser(intent, title)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            "[成功] 已打开分享面板"
        } catch (e: Exception) {
            "[失败] 分享失败: ${e.message}"
        }
    }

    /** 拨打电话 */
    private fun handleDialPhone(params: JSONObject): String {
        val phone = params.optString("phone", "")

        if (phone.isBlank()) return "[失败] 拨号失败：缺少手机号码"
        if (!isPhoneNumber(phone)) return "[失败] 拨号失败：手机号码格式不正确"

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            "[成功] 已打开拨号面板 → $phone"
        } catch (e: Exception) {
            "[失败] 拨号失败: ${e.message}"
        }
    }
}
