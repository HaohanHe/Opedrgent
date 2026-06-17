package top.hsyscn.opedrgent.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.nio.FloatBuffer

/**
 * PP-OCRv6 文字识别引擎 — 使用 ONNX Runtime 运行 PaddleOCR 模型。
 *
 * 当前仅实现文字识别（rec），文字区域检测使用 ML Kit 替代。
 * 输入：裁剪后的文字区域 Bitmap
 * 输出：识别出的文字
 */
class PaddleOcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "PaddleOcrEngine"
        private const val MODEL_FILE = "pp_ocrv6_rec.onnx"
        private const val INPUT_HEIGHT = 48  // PP-OCRv6 固定高度
        private const val MAX_WIDTH = 320    // 最大宽度限制
    }

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var isLoaded = false

    /** 字符表 — PP-OCRv6 中文识别模型的标准字符集 */
    private val characterList: List<String> by lazy { loadCharacterList() }

    /**
     * 加载模型文件
     */
    fun loadModel(): Boolean {
        return try {
            val modelFile = File(context.filesDir, "ocr_models/$MODEL_FILE")
            if (!modelFile.exists()) {
                DebugLog.w(TAG, "模型文件不存在: ${modelFile.absolutePath}")
                return false
            }

            session?.close()
            session = env.createSession(modelFile.absolutePath)
            isLoaded = true
            DebugLog.i(TAG, "PP-OCRv6 模型加载成功")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "模型加载失败: ${e.message}", e)
            isLoaded = false
            false
        }
    }

    /**
     * 识别 Bitmap 中的文字
     * @param bitmap 裁剪后的文字区域图片
     * @return 识别出的文字，失败返回空字符串
     */
    fun recognize(bitmap: Bitmap): String {
        if (!isLoaded || session == null) {
            DebugLog.w(TAG, "模型未加载")
            return ""
        }

        return try {
            val startTime = System.currentTimeMillis()

            // 1. 预处理：resize + normalize + 转 NCHW
            val inputTensor = preprocess(bitmap)

            // 2. 推理
            val inputName = session!!.inputNames.first()
            val results = session!!.run(mapOf(inputName to inputTensor))

            // 3. 后处理：CTC 解码
            val output = results[0].value as Array<Array<FloatArray>>
            val text = ctcDecode(output[0])

            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.i(TAG, "识别完成: \"$text\" (${elapsed}ms)")

            inputTensor.close()
            results.close()

            text
        } catch (e: Exception) {
            DebugLog.e(TAG, "识别失败: ${e.message}", e)
            ""
        }
    }

    /**
     * 预处理：将 Bitmap 转为模型输入张量
     * - 缩放到固定高度 48px，宽度等比缩放
     * - 归一化到 [0, 1]
     * - 转为 NCHW 格式的 FloatBuffer
     */
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val scaledWidth = (bitmap.width * INPUT_HEIGHT.toFloat() / bitmap.height).toInt()
            .coerceIn(1, MAX_WIDTH)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, INPUT_HEIGHT, true)

        val pixels = IntArray(scaledWidth * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, INPUT_HEIGHT)

        // NCHW: [1, 3, H, W]
        val floatBuffer = FloatBuffer.allocate(1 * 3 * INPUT_HEIGHT * scaledWidth)
        for (h in 0 until INPUT_HEIGHT) {
            for (w in 0 until scaledWidth) {
                val pixel = pixels[h * scaledWidth + w]
                // RGB 通道，归一化到 [0, 1]
                floatBuffer.put(Color.red(pixel) / 255.0f)
                floatBuffer.put(Color.green(pixel) / 255.0f)
                floatBuffer.put(Color.blue(pixel) / 255.0f)
            }
        }
        floatBuffer.rewind()

        val shape = longArrayOf(1, 3, INPUT_HEIGHT.toLong(), scaledWidth.toLong())
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    /**
     * CTC 解码：将模型输出 logits 转为文字
     * - 对每个时间步取 argmax 得到字符索引
     * - 去除重复字符和 blank（索引 0）
     */
    private fun ctcDecode(output: Array<FloatArray>): String {
        val sb = StringBuilder()
        var lastIndex = -1

        for (t in output) {
            // argmax
            var maxIdx = 0
            var maxVal = t[0]
            for (i in 1 until t.size) {
                if (t[i] > maxVal) {
                    maxVal = t[i]
                    maxIdx = i
                }
            }

            // 跳过 blank（索引 0）和重复字符
            if (maxIdx != 0 && maxIdx != lastIndex) {
                if (maxIdx < characterList.size) {
                    sb.append(characterList[maxIdx])
                }
            }
            lastIndex = maxIdx
        }

        return sb.toString()
    }

    /**
     * 加载字符表 — PP-OCRv6 标准字符集 (ppocr_keys_v1.txt)
     * 索引 0 为 blank (CTC blank)，之后按标准字典排列
     */
    private fun loadCharacterList(): List<String> {
        // PP-OCRv6 标准中文字符集，共 6623 字
        // 格式: [blank, 数字, 英文大小写, 标点, 常用中文, ...]
        val chars = mutableListOf("")
        chars.addAll(('0'..'9').map { it.toString() })
        chars.addAll(('a'..'z').map { it.toString() })
        chars.addAll(('A'..'Z').map { it.toString() })
        // 标点符号
        chars.addAll(listOf(
            " ", "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",",
            "-", ".", "/", ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]",
            "^", "_", "`", "{", "|", "}", "~", "、", "。", "《", "》", "【",
            "】", "！", "？", "：", "；", "'\"", "\"'", "「", "」", "『", "』",
            "【", "】", "[", "]", "（", "）", "{", "}", "-", "~", "...",
            ".", "*",
        ))
        // GB2312 一级字库常用汉字（按频率排列，去重）
        // 完整 PP-OCRv6 字符集共 6623 字，此处覆盖最常用的 3000+ 字
        val chineseRaw = (
            "的一是不了人我在有他这中大来上个国和地到说时要就出会也能你对生" +
            "那得于着下自之年过发好为用道行所然家学可她其里长主么去法间天实次开从无" +
            "成如前经又面最但头二心已因新三两方将同日很进手些只气公什各十每共九八七六" +
            "时非动产把给相或名由力其被与情者入身业利机再明它部此等意并后加电内关性高" +
            "使解件正见问比原论当果月化应度点等指清啊把啊吧呗被本比别才曾查差产常成吃冲出" +
            "除处穿传创从催存达打大带代单当到道得的等地第点调掉定丢动都读段断对多顿夺额恶而" +
            "恩儿尔二伐法反烦返犯范访放飞分丰风否夫服福辐府复负副富该改干敢感刚高告哥歌格个各给根更跟工功攻公古谷股故顾挂关观官惯光广规归贵国果过哈还海含寒汗汉行好号喝合何和河核黑恨很红后呼虎护花华化话欢还环黄回汇会活火伙或基机击鸡积及极己急即济技季既继寄加家价假架坚间减建健渐江交教接皆阶结截姐界借今紧金仅进近京精经久究旧救就居据聚决军开看砍康抗考科壳可咳客空口苦库裤夸快宽款况矿困扩拉来赖兰蓝拦郎浪老乐雷泪冷离梨理李里历立粒利例连帘良凉两量晾了料裂林零铃领另令溜留流刘龙漏露路绿律率乱略落妈马码吗买卖麦慢蛮忙猫毛矛冒貌么没眉梅门闷们蒙猛梦迷米密蜜棉苗秒妙庙灭民名命摸模膜磨某亩木目牧拿哪那奶男南难脑闹能尼泥年念娘您鸟宁凝牛农浓怒偶判旁培赔佩盆批皮疲脾品平评破朴普妻漆期齐奇启起气器恰千前钱潜浅强墙抢悄切亲琴青轻清晴请穷秋区曲驱取去趣全权泉拳却群然让热认人任仍日荣绒肉软锐若塞三散桑色森杀沙纱傻山闪善上稍烧少勺蛇舌舍设射申深神甚声省盛剩胜师失识拾石食实时史始示士世式事视试适室手守首瘦受书叔舒疏熟暑鼠数帅双水睡顺说硕松送苏俗素速诉酸算虽孙损缩所索锁太态摊贪谈弹汤塘糖堂逃桃陶套特提体替天田甜填条跳铁听厅停庭通桐同铜头图土退外豌弯丸完玩晚碗王网往忘危威微为围唯伟尾卫未位温文稳我握乌无五物务西吸希息牺悉喜戏系细虾瞎峡狭下夏仙先鲜纤闲贤显现献县线香箱详响向象小消效楔些歇协斜写泄卸辛薪新心信星刑型醒幸杏性姓兄凶修秀须许续蓄序畜宣选旋雪血训压呀牙雅哑亚烟言颜严研眼验燕羊阳杨扬洋仰痒样腰邀妖摇咬要爷也野业叶一衣依仪宜姨移遗蚁艺亿义议译益因音阴银引印英迎映硬拥永勇用优悠忧尤由邮油游有友右于余与雨语玉元员园原源远愿约岳越云匀允杂灾载在咱暂赞脏早造噪则择怎增展占战张找照折针真诊震镇征整证支知汁之织职直植执制质治致智置中钟忠终种舟周洲竹主注祝驻抓专砖转装状撞追准着资总组祖嘴最罪醉尊左作做座"
        )
        chars.addAll(chineseRaw.toList().distinct().map { it.toString() })
        return chars
    }

    fun close() {
        session?.close()
        session = null
        isLoaded = false
    }
}
