package top.hsyscn.opedrgent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.hsyscn.opedrgent.note.NoteType

/**
 * 笔记类型标签的主题感知配色。
 */
@Composable
fun noteTypeContainerColor(type: NoteType): Color {
    val dark = isSystemInDarkTheme()
    return when (type) {
        NoteType.TEXT -> if (dark) PrimaryRing.copy(alpha = 0.18f) else Color(0xFFE5E9FF)
        NoteType.ASR -> if (dark) Warning.copy(alpha = 0.18f) else Color(0xFFFFF7ED)
        NoteType.MEETING -> if (dark) Success.copy(alpha = 0.18f) else Color(0xFFECFDF5)
        NoteType.LINK -> if (dark) Color(0xFF14B8A6).copy(alpha = 0.18f) else Color(0xFFF0FDFA)
        NoteType.QUICK -> if (dark) Color(0xFFEAB308).copy(alpha = 0.18f) else Color(0xFFFEFCE8)
        NoteType.AI_CHAT -> if (dark) AccentPurple.copy(alpha = 0.18f) else Color(0xFFF5F3FF)
        NoteType.IMAGE -> if (dark) Color(0xFFEC4899).copy(alpha = 0.18f) else Color(0xFFFDF2F8)
        NoteType.PDF -> if (dark) Error.copy(alpha = 0.18f) else Color(0xFFFFF5F5)
        NoteType.AUDIO -> if (dark) Color(0xFF0EA5E9).copy(alpha = 0.18f) else Color(0xFFF0F9FF)
        NoteType.BOOK -> if (dark) Color(0xFF78716C).copy(alpha = 0.25f) else Color(0xFFF5F5F4)
    }
}

@Composable
fun noteTypeContentColor(type: NoteType): Color {
    val dark = isSystemInDarkTheme()
    return when (type) {
        NoteType.TEXT -> if (dark) PrimaryRing else Color(0xFF3B7AFF)
        NoteType.ASR -> if (dark) Warning else Color(0xFFF97316)
        NoteType.MEETING -> if (dark) Success else Color(0xFF22C55E)
        NoteType.LINK -> if (dark) Color(0xFF2DD4BF) else Color(0xFF14B8A6)
        NoteType.QUICK -> if (dark) Color(0xFFFDE047) else Color(0xFFEAB308)
        NoteType.AI_CHAT -> if (dark) AccentPurple else Color(0xFF8B5CF6)
        NoteType.IMAGE -> if (dark) Color(0xFFF472B6) else Color(0xFFEC4899)
        NoteType.PDF -> if (dark) Color(0xFFFCA5A5) else Color(0xFFEF4444)
        NoteType.AUDIO -> if (dark) Color(0xFF7DD3FC) else Color(0xFF0EA5E9)
        NoteType.BOOK -> if (dark) Color(0xFFA8A29E) else Color(0xFF78716C)
    }
}
