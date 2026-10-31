package com.voiceassistant.model

/**
 * 应用内可下载的模型清单。
 *
 * 全部来自 sherpa-onnx 官方 Releases（GitHub），仓库里不含任何模型文件。
 * 体积是最新一次记录值，仅用于界面上给用户一个预期，不参与校验。
 */
object ModelCatalog {

    data class Entry(
        val id: String,
        val kind: ModelKind,
        val title: String,
        val url: String,
        val approxMb: Int,
        val note: String
    )

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val entries: List<Entry> = listOf(
        // ---------------- 唤醒 KWS ----------------
        Entry(
            id = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile",
            kind = ModelKind.KWS,
            title = "中文唤醒（手机版，推荐）",
            url = "$BASE/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2",
            approxMb = 15,
            note = "体积小、适合常驻；唤醒词写在 keywords.txt，改词不用重训"
        ),
        Entry(
            id = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01",
            kind = ModelKind.KWS,
            title = "中文唤醒（完整版）",
            url = "$BASE/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2",
            approxMb = 32,
            note = "识别率略高，体积翻倍"
        ),

        // ---------------- 识别 ASR ----------------
        Entry(
            id = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            kind = ModelKind.ASR,
            title = "SenseVoice 多语种（推荐）",
            url = "$BASE/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
            approxMb = 230,
            note = "中文效果最好，支持中英日韩粤；建议在 Wi-Fi 下下载"
        ),

        // ---------------- 合成 TTS ----------------
        Entry(
            id = "vits-zh-hf-fanchen-C",
            kind = ModelKind.TTS,
            title = "TTS 女声（22.05 kHz，推荐）",
            url = "$BASE/tts-models/vits-zh-hf-fanchen-C.tar.bz2",
            approxMb = 114,
            note = "音质明显优于 8 kHz 电话音质方案"
        ),
        Entry(
            id = "vits-zh-hf-theresa",
            kind = ModelKind.TTS,
            title = "TTS 女声 theresa（22.05 kHz）",
            url = "$BASE/tts-models/vits-zh-hf-theresa.tar.bz2",
            approxMb = 115,
            note = "另一个高保真选择"
        ),
        Entry(
            id = "vits-zh-aishell3",
            kind = ModelKind.TTS,
            title = "TTS aishell3（174 发音人，8 kHz）",
            url = "$BASE/tts-models/vits-zh-aishell3.tar.bz2",
            approxMb = 140,
            note = "老方案，8 kHz 电话音质，仅兼容用"
        )
    )

    fun byKind(kind: ModelKind): List<Entry> = entries.filter { it.kind == kind }

    fun find(id: String): Entry? = entries.firstOrNull { it.id == id }
}
