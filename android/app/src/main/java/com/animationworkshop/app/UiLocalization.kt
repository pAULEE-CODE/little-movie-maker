package com.animationworkshop.app

import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

internal val LocalUiLanguageMode = compositionLocalOf { "BILINGUAL" }

/** Shared UI copy resolver. Existing paired labels remain the source of truth for bilingual text. */
internal fun resolveUiText(source: String, mode: String): String {
    val normalizedMode = mode.uppercase()
    Regex("(\\d+) frames / 張\\s+·\\s+(.+)").matchEntire(source)?.let { match ->
        val count = match.groupValues[1]
        val date = match.groupValues[2]
        return when (normalizedMode) {
            "ENGLISH" -> "$count frames · $date"
            "TRADITIONAL_CHINESE" -> "$count 格 · $date"
            else -> "$count frames / $count 格 · $date"
        }
    }
    val pair = source.split(" / ", limit = 2)
    val translatedPair = pair.size == 2 && pair[1].any { it in '\u3400'..'\u9fff' } &&
        pair[1].matches(Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff0-9\\s（）()？！，、·＋＋!！]+"))
    if (translatedPair) {
        return when (normalizedMode) {
            "ENGLISH" -> pair[0]
            "TRADITIONAL_CHINESE" -> pair[1]
            else -> source
        }
    }

    val english = englishToChinese.entries.sortedByDescending { it.key.length }.firstOrNull { (label, _) ->
        source == label || source.startsWith("$label ") || source.startsWith("$label:")
    }
    val reverseTranslation = englishToChinese.entries.firstOrNull { (_, translation) -> source == translation }?.key
    val chinese = when {
        source.matches(Regex("Frame (\\d+) of (\\d+)")) -> source.replace(Regex("Frame (\\d+) of (\\d+)"), "第$1格，共$2格")
        source.matches(Regex("(\\d+) selected")) -> source.replace(Regex("(\\d+) selected"), "已選取$1個")
        source.matches(Regex("(\\d+) frames?")) -> source.replace(Regex("(\\d+) frames?"), "$1格")
        source.matches(Regex("Frame (\\d+) thumbnail")) -> source.replace(Regex("Frame (\\d+) thumbnail"), "第$1格縮圖")
        source.matches(Regex("Frame (\\d+) at the split point")) -> source.replace(Regex("Frame (\\d+) at the split point"), "分割位置第$1格")
        source.matches(Regex("(\\d+) FPS")) -> source.replace(" FPS", " 幀/秒")
        source.matches(Regex("Movie length: (.+) seconds")) -> source.replace(Regex("Movie length: (.+) seconds"), "影片長度：$1秒")
        source.matches(Regex("Estimated duration: (.+)")) -> source.replace("Estimated duration: ", "預計片長：")
        source.matches(Regex("Resolution: (.+)")) -> source.replace("Resolution: ", "解析度：")
        source.matches(Regex("Animation speed: (.+)")) -> source.replace("Animation speed: ", "動畫速度：")
        source.matches(Regex("Default speed · (\\d+) FPS")) -> source.replace(Regex("Default speed · (\\d+) FPS"), "預設速度 · $1 幀/秒")
        source.matches(Regex("Speed · (\\d+) FPS")) -> source.replace(Regex("Speed · (\\d+) FPS"), "速度 · $1 幀/秒")
        source.matches(Regex("(\\d+) selected")) -> source.replace(Regex("(\\d+) selected"), "已選取$1個")
        source.matches(Regex("Taking a photo every (\\d+) seconds")) -> source.replace(Regex("Taking a photo every (\\d+) seconds"), "每$1秒拍攝一張照片")
        source.matches(Regex("(\\d+) source photos captured")) -> source.replace(Regex("(\\d+) source photos captured"), "已拍攝$1張來源照片")
        source.matches(Regex("Selected hold · (.+) \\((\\d+)\\)")) -> source.replace(Regex("Selected hold · (.+) \\((\\d+)\\)"), "所選影格停留 · $1（$2格）")
        source.matches(Regex("All holds · ×(\\d+)")) -> source.replace(Regex("All holds · ×(\\d+)"), "全部停留 · ×$1")
        source.matches(Regex("(\\d+)% complete")) -> source.replace(Regex("(\\d+)% complete"), "完成$1%")
        source.matches(Regex("(\\d+) total frames")) -> source.replace(Regex("(\\d+) total frames"), "共$1格")
        source.matches(Regex("(\\d+) frames left")) -> source.replace(Regex("(\\d+) frames left"), "還有$1格")
        source.matches(Regex("(.+) until stop time")) -> source.replace(Regex("(.+) until stop time"), "距停止時間 $1")
        source.matches(Regex("(\\d+) movie frames  ·  (.+) elapsed")) -> source.replace(Regex("(\\d+) movie frames  ·  (.+) elapsed"), "已拍攝$1格 · 已錄製$2")
        source.matches(Regex("Delete (\\d+) movies\\?")) -> source.replace(Regex("Delete (\\d+) movies\\?"), "刪除$1部電影？")
        source.matches(Regex("Frame (\\d+)  ·  Tap to review")) -> source.replace(Regex("Frame (\\d+)  ·  Tap to review"), "第$1格 · 點按查看")
        source.matches(Regex("ISO · (.+)")) -> source
        source.matches(Regex("Your latest frame, frame (\\d+)\\. Tap to review\\.")) -> source.replace(Regex("Your latest frame, frame (\\d+)\\. Tap to review\\."), "最新影格，第$1格。點按查看。")
        source == "Mixed" -> "混合"
        source == "MODES. Open mode selection" -> "模式。開啟模式選擇"
        source == "Little Movie Maker cartoon workshop with children making stop-motion animations" -> "小小電影製作家卡通工作室，孩子們正在製作逐格動畫"
        source == "Timeline" -> "時間軸"
        source == "Select" -> "選取"
        source == "Range" -> "範圍"
        source == "Scroll" -> "捲動"
        source == "Turn delete confirmation on" -> "重新啟用刪除確認"
        source == "Rotate movie" -> "旋轉電影"
        source == "Move frame" -> "移動影格"
        source == "Default timeline thumbnail size" -> "預設時間軸縮圖大小"
        source == "Medium" -> "中"
        source == "Open" -> "開啟"
        source == "Save in" -> "儲存至"
        source == "Total frames" -> "影格總數"
        source == "Frame count" -> "影格數量"
        source == "Duration" -> "時長"
        source == "Clock time" -> "指定時間"
        source == "Frame count" -> "影格數量"
        source == "The selected movies and their photographs will be removed from this device." -> "所選電影及其照片將從此裝置移除。"
        source == "Deletion confirmation will be turned back on for every movie." -> "將為每部電影重新啟用刪除確認。"
        source == "This photo will be removed from your animation." -> "這張照片會從動畫中移除。"
        source == "These photos will be removed from your animation." -> "這些照片會從動畫中移除。"
        source == "Made for playful stop-motion stories." -> "為有趣的逐格動畫故事而設。"
        source == "The workshop is ready for a story!" -> "工作室已準備好，開始創作故事吧！"
        source == "Paused" -> "已暫停"
        source == "Recording" -> "錄製中"
        source == "Finished" -> "已完成"
        source == "Open Movie" -> "開啟影片"
        source == "Export could not finish" -> "匯出未能完成"
        source == "Movie saved!" -> "影片已儲存！"
        source == "1st\nphoto" -> "第一張\n照片"
        source == "Move a little, then take a picture!" -> "稍微移動一下，然後拍照！"
        source == "Delete this movie?" -> "刪除此電影？"
        source == "This empty folder will be removed." -> "將移除此空資料夾。"
        source.startsWith("This folder contains movies or folders.") -> "此資料夾含有電影或資料夾，內容將移至上一層；不會刪除電影。"
        source == "Timeline" -> "時間軸"
        source == "Select" -> "選取"
        source == "Turn delete confirmation on" -> "重新啟用刪除確認"
        source == "Change every frame?" -> "修改所有影格？"
        source == "Back to camera" -> "返回相機"
        source == "Back to camera  📷" -> "返回相機 📷"
        source == "Stop and continue" -> "停止並繼續"
        source == "Keep recording" -> "繼續錄製"
        source == "Stop this timelapse?" -> "停止這次縮時攝影？"
        source == "Stop and switch" -> "停止並切換"
        source == "Stop and leave" -> "停止並離開"
        source == "Move frame" -> "移動影格"
        source == "Rotate movie" -> "旋轉電影"
        source == "Delete this frame?" -> "刪除此影格？"
        source == "Part A name" -> "A 部分名稱"
        source == "Part B name" -> "B 部分名稱"
        source == "Create both movies" -> "建立兩部電影"
        source == "Middle" -> "中"
        source == "Number of photographs" -> "照片數量"
        source == "Seconds between photos" -> "照片間隔秒數"
        source == "Manual exposure" -> "手動曝光"
        source == "Choose a capture interval longer than the shutter plus 0.3 seconds." -> "拍攝間隔需長於快門時間再加 0.3 秒。"
        source == "Uses the rear camera's reported shutter and ISO ranges." -> "使用後置相機回報的快門與 ISO 範圍。"
        source == "Manual shutter control is not supported by this camera. Automatic exposure will be used." -> "此相機不支援手動快門，將使用自動曝光。"
        source.startsWith("Combines ") -> source.replaceFirst("Combines ", "合併 ").replace(" source photos into each movie frame. A stable camera position gives the best results.", " 張來源照片成為每個電影影格。固定相機位置可得到最佳效果。")
        source.startsWith("Choose an interval long enough") -> "請設定足夠長的拍攝間隔；相機處理時間可能會延長間隔。"
        source.startsWith("Set all ") -> source.replaceFirst("Set all ", "將全部 ").replace(" photographs to ", " 張照片設為 ").replace(" duration?", " 倍停留時間？")
        source.startsWith("Delete folder · ") -> source.replaceFirst("Delete folder · ", "刪除資料夾 · ")
        source.startsWith("Move folder · ") -> source.replaceFirst("Move folder · ", "移動資料夾 · ")
        source.startsWith("Move selected movies") -> "移動所選電影"
        source.startsWith("Saved in Movies/AnimationWorkshop") -> "已儲存至 Movies/AnimationWorkshop"
        source.startsWith("Movie length: ") -> source.replaceFirst("Movie length: ", "影片長度：").replace(" seconds", " 秒")
        source.startsWith("Frame ") && source.endsWith("  ·  Tap to review") -> source.replace(Regex("Frame (\\d+)  ·  Tap to review"), "第$1格 · 點按查看")
        source.startsWith("FRAME ") -> source.replaceFirst("FRAME ", "影格 ")
        source == "TIMELAPSE" -> "縮時攝影"
        source == "Manual camera" -> "手動拍攝"
        source == "Back to camera  📷" -> "返回相機 📷"
        source.startsWith("Delete ") && source.endsWith(" movies?") -> source.replaceFirst("Delete ", "刪除 ").replace(" movies?", " 部電影？")
        source.startsWith("Frame ") && source.endsWith(" of ") -> source
        source == "Pause Ⅱ" -> "暫停 Ⅱ"
        source == "Resume ▶" -> "繼續 ▶"
        source == "Play ▶" -> "播放 ▶"
        source == "Range" -> "範圍"
        source == "Scroll" -> "捲動"
        source == "Mixed" -> "混合"
        source == "Shutter" -> "快門"
        source == "Hours" -> "小時"
        source == "Minutes" -> "分鐘"
        source == "Off" -> "關閉"
        source == "Take a photo every…" -> "每隔一段時間拍照…"
        source == "Stop automatically…" -> "自動停止條件…"
        source == "Make the workshop comfortable for you." -> "調整工作室，讓你使用得更舒適。"
        source == "Your photos are saved in this project." -> "照片已儲存在此專案。"
        source == "Manual camera" -> "手動拍攝"
        source == "Let’s use the camera" -> "開始使用相機吧"
        source == "Go back" -> "返回"
        source == "Light trails" -> "光軌"
        source == "Stop at " -> "於"
        source.startsWith("Stop at ") -> source.replaceFirst("Stop at ", "於")
        source.startsWith("Default timeline thumbnail size · ") -> source.replaceFirst("Default timeline thumbnail size · ", "預設時間軸縮圖大小 · ")
        source.startsWith("The current timelapse will stop.") -> "目前的縮時攝影將會停止。已拍攝的照片會保留在專案中。"
        source.startsWith("Leaving stops the recording.") -> "離開將停止拍攝。已拍攝的照片會保留在專案中。"
        source.startsWith("This photo will be removed") -> "這張照片會從動畫中移除。"
        source.startsWith("These photos will be removed") -> "這些照片會從動畫中移除。"
        source.startsWith("The selected movies and their photographs") -> "所選電影及其照片將從此裝置移除。"
        source.startsWith("Deletion confirmation will be turned back on") -> "將為每部電影重新啟用刪除確認。"
        source.startsWith("Camera permission is needed") -> "需要相機權限才能拍照。"
        source.startsWith("We need camera access") -> "需要相機權限才能拍攝電影影格。"
        source.startsWith("Animated preview of ") -> source.replaceFirst("Animated preview of ", "動畫預覽：")
        source.startsWith("Latest animation photograph") -> "最新動畫照片"
        source.startsWith("Most recent animation photo") -> "最近拍攝的動畫照片"
        else -> english?.let { (label, translation) -> source.replaceFirst(label, translation) }
    }
    return when (normalizedMode) {
        "ENGLISH" -> reverseTranslation ?: source
        "TRADITIONAL_CHINESE" -> chinese ?: source
        else -> when {
            reverseTranslation != null -> "$reverseTranslation / $source"
            chinese == null || chinese == source -> source
            else -> "$source / $chinese"
        }
    }
}

@Composable
internal fun uiText(source: String): String = resolveUiText(source, LocalUiLanguageMode.current)

@Composable
internal fun LocalizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle? = null
) {
    MaterialText(
        text = uiText(text), modifier = modifier, color = color, fontSize = fontSize,
        fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout,
        style = style ?: androidx.compose.material3.LocalTextStyle.current
    )
}

private val englishToChinese = linkedMapOf(
    "MODES" to "模式", "Preview" to "預覽", "Edit" to "編輯", "Camera" to "相機", "Timelapse" to "縮時攝影",
    "Export Movie" to "匯出影片", "Projects" to "專案", "Settings" to "設定", "Back" to "返回",
    "Home" to "首頁", "New Movie" to "新增電影", "Create" to "建立", "Save" to "儲存", "Cancel" to "取消",
    "Done" to "完成", "Delete" to "刪除", "Keep" to "保留", "Copy" to "複製", "Duplicate" to "複製",
    "Rename" to "改名", "Move" to "移動", "Merge" to "合併", "Export" to "匯出", "Share" to "分享",
    "Open" to "開啟", "Play" to "播放", "Pause" to "暫停", "Resume" to "繼續", "STOP" to "停止",
    "Next" to "下一張", "Previous" to "上一張", "Frame" to "影格", "Movie" to "電影",
    "My Little Movies" to "我的小電影", "Location" to "位置", "Root" to "根目錄", "Parent" to "上一層",
    "Folder" to "資料夾", "New Folder" to "新增資料夾", "Large" to "大圖", "Small" to "小圖", "List" to "清單",
    "selected" to "已選取", "Your movie shelf is ready for a story!" to "電影架已準備好，開始創作故事吧！",
    "Your photos will appear here" to "你的照片會顯示在這裡", "This movie is ready for its first frame" to "這部電影已準備好拍攝第一格",
    "Preview photo unavailable" to "預覽照片無法使用", "Photo is loading…" to "照片載入中…",
    "Your photo" to "你的照片", "No frames yet" to "還沒有影格", "No frames yet  ·  Start a movie" to "還沒有影格 · 開始拍攝電影",
    "Tap to review" to "點按查看", "Open Movie" to "開啟影片", "Movie saved!" to "影片已儲存！",
    "Making your movie…" to "正在製作影片…", "Checking video encoder…" to "正在檢查影片編碼器…",
    "Estimated duration" to "預計片長", "Resolution" to "解析度", "Animation speed" to "動畫速度",
    "Speed" to "速度", "Default speed" to "預設速度", "Delete this movie?" to "刪除此電影？",
    "Delete folder" to "刪除資料夾", "Rename Folder" to "重新命名資料夾", "Rename Movie" to "重新命名電影",
    "Copy Movie" to "複製電影", "Movie name" to "電影名稱", "New movie name" to "新電影名稱",
    "Create both movies" to "建立兩部電影", "Create merged movie" to "建立合併電影", "Merge movies" to "合併電影",
    "Playback speed" to "播放速度", "Settings" to "設定", "Theme" to "主題", "Language" to "語言",
    "English" to "英文", "Traditional Chinese" to "繁體中文", "Bilingual" to "雙語",
    "Light" to "淺色", "Dark" to "深色", "Follow device" to "跟隨系統", "New movies" to "新電影",
    "Default timeline thumbnail size" to "預設時間軸縮圖大小", "Projects" to "專案", "Automatic animated preview" to "自動播放專案預覽",
    "Countdown sound" to "倒數提示音", "Editor" to "編輯器", "Delete warnings are saved per movie." to "每部電影分別儲存刪除確認設定。",
    "Restore all delete warnings" to "恢復所有刪除確認", "About" to "關於", "Made for playful stop-motion stories." to "為有趣的逐格動畫故事而設。",
    "Restore delete warnings?" to "恢復刪除確認？", "Restore" to "恢復", "Allow camera" to "允許相機",
    "Go back" to "返回", "Let’s use the camera" to "開始使用相機吧", "We need camera access so you can take your movie frames." to "需要相機權限才能拍攝電影影格。",
    "Effects" to "特效", "Coming Soon!" to "即將推出！", "Manual camera" to "手動拍攝",
    "Preview ▶" to "預覽 ▶", "Timelapse ⏱" to "縮時攝影 ⏱", "Stop timelapse and switch?" to "停止縮時攝影並切換？",
    "Stop and continue" to "停止並繼續", "Keep recording" to "繼續錄製", "Your photos are saved in this project." to "照片已儲存在此專案。",
    "Back to camera" to "返回相機", "Your latest frame" to "最新影格", "Latest animation photograph" to "最新動畫照片",
    "Open mode selection" to "開啟模式選擇", "Default timeline thumbnail size" to "預設時間軸縮圖大小"
)
