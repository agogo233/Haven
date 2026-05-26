package sh.haven.core.toolbar

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.connectbot.terminal.SelectionController
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarKey
import kotlinx.coroutines.delay
import sh.haven.core.data.preferences.ToolbarLayout
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import sh.haven.core.data.preferences.MACRO_PRESETS
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

// VT100/xterm escape sequences for special keys
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
// Carriage return — what the physical Enter key sends; the tty line
// discipline maps it to newline. Lets the user submit a command (e.g. after
// recalling it with the Up-arrow key) without raising the soft keyboard (#184).
private val KEY_ENTER = byteArrayOf(0x0d)
private val KEY_SHIFT_TAB = "$ESC[Z".toByteArray()
private val KEY_HOME = "$ESC[H".toByteArray()
private val KEY_END = "$ESC[F".toByteArray()
private val KEY_PGUP = "$ESC[5~".toByteArray()
private val KEY_PGDN = "$ESC[6~".toByteArray()

/** Keys that form the aligned navigation block across rows. */
private val NAV_KEYS = setOf(
    ToolbarKey.ARROW_UP, ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_RIGHT,
    ToolbarKey.HOME, ToolbarKey.END,
    ToolbarKey.PGUP, ToolbarKey.PGDN,
)

/**
 * Fixed grid positions for the nav block.
 * Row 0 (top):    [Home] [ ↑ ] [End] [PgUp]
 * Row 1 (bottom): [ ← ] [ ↓ ] [ → ] [PgDn]
 */
private val NAV_GRID_TOP = arrayOf(
    ToolbarKey.HOME,
    ToolbarKey.ARROW_UP,
    ToolbarKey.END,
    ToolbarKey.PGUP,
)
private val NAV_GRID_BOTTOM = arrayOf(
    ToolbarKey.ARROW_LEFT,
    ToolbarKey.ARROW_DOWN,
    ToolbarKey.ARROW_RIGHT,
    ToolbarKey.PGDN,
)

/** Width of each cell in the nav block grid. */
private val NAV_CELL_WIDTH = 44.dp

/** Bundled callbacks threaded through the toolbar composable hierarchy. */
data class ToolbarCallbacks(
    val onSendBytes: (ByteArray) -> Unit,
    val onDispatchKey: (Int, Int) -> Unit,
    val onToggleCtrl: () -> Unit,
    val onToggleAlt: () -> Unit,
    val onToggleShift: () -> Unit,
    val onShiftUsed: () -> Unit,
    val bracketPasteMode: Boolean = false,
    val clipboardManager: ClipboardManager? = null,
    val onPaste: (String) -> Unit = {},
    val onEnterReorderMode: () -> Unit = {},
    val onAddCustomKey: (ToolbarItem.Custom) -> Unit = {},
    val snippets: List<ToolbarItem.Custom> = emptyList(),
    val onSnippetTap: (ToolbarItem.Custom) -> Unit = {},
    val onAddSnippet: (ToolbarItem.Custom) -> Unit = {},
    val onDeleteSnippet: (ToolbarItem.Custom) -> Unit = {},
    /**
     * Current state of the "standard keyboard" preference — when true the
     * terminal's IME runs in full-featured mode (voice input, swipe,
     * autocomplete); when false it's the secure/terminal mode with no
     * suggestions. Drives the visual on/off tint of the Voice toolbar key.
     */
    val allowStandardKeyboard: Boolean = false,
    /** Flip [allowStandardKeyboard]. Usually persisted to preferences. */
    val onToggleStandardKeyboard: () -> Unit = {},
    /**
     * Raw keyboard mode — when true the terminal returns no InputConnection
     * at all, so Gboard has nothing to decorate and its mic, suggestion
     * strip and AI Core writing assist cannot appear. Drives the Raw
     * toolbar key's on/off tint. Mutually exclusive with
     * [allowStandardKeyboard].
     */
    val rawKeyboardMode: Boolean = false,
    /** Flip [rawKeyboardMode]. */
    val onToggleRawKeyboard: () -> Unit = {},
    /**
     * Tap on the paperclip / attach key. Opens the source picker (SAF) so the
     * user can send a local file into the active session and inject a usable
     * reference (path or share URL) at the cursor.
     */
    val onAttachTap: () -> Unit = {},
)

val LocalToolbarCallbacks = compositionLocalOf<ToolbarCallbacks> {
    error("ToolbarCallbacks not provided")
}

/** Default minimum width for a toolbar key (0 = hug content; overridable via the Haven setting). */
val DEFAULT_MIN_KEY_WIDTH = 0.dp

/**
 * Minimum width every toolbar key is stretched to, threaded from the user's
 * "minimum key width" setting via [KeyboardToolbar]. Read by the shared
 * [ToolbarKeyButton] primitive so every key honours it uniformly.
 */
private val LocalToolbarMinKeyWidth = compositionLocalOf { DEFAULT_MIN_KEY_WIDTH }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardToolbar(
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (modifiers: Int, key: Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    bracketPasteMode: Boolean = false,
    layout: ToolbarLayout = ToolbarLayout.DEFAULT,
    navBlockMode: sh.haven.core.data.preferences.NavBlockMode = sh.haven.core.data.preferences.NavBlockMode.ALIGNED,
    minKeyWidth: Dp = DEFAULT_MIN_KEY_WIDTH,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    onVncTap: (() -> Unit)? = null,
    vncLoading: Boolean = false,
    selectionController: SelectionController? = null,
    selectionActive: Boolean = false,
    hyperlinkUri: String? = null,
    onPaste: (String) -> Unit = {},
    reorderMode: Boolean = false,
    onReorderModeChanged: (Boolean) -> Unit = {},
    onToolbarLayoutChanged: (ToolbarLayout) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    selectionContent: (@Composable () -> Unit)? = null,
    allowStandardKeyboard: Boolean = false,
    onToggleStandardKeyboard: () -> Unit = {},
    rawKeyboardMode: Boolean = false,
    onToggleRawKeyboard: () -> Unit = {},
    onAttachTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var shiftActive by remember { mutableStateOf(false) }
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible

    val clipboardManager = remember {
        view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    val allSnippets = remember(layout) {
        layout.rows.flatMap { row -> row.filterIsInstance<ToolbarItem.Custom>() }
    }

    val callbacks = ToolbarCallbacks(
        onSendBytes = onSendBytes,
        onDispatchKey = onDispatchKey,
        onToggleCtrl = onToggleCtrl,
        onToggleAlt = onToggleAlt,
        onToggleShift = { shiftActive = !shiftActive },
        onShiftUsed = { shiftActive = false },
        bracketPasteMode = bracketPasteMode,
        clipboardManager = clipboardManager,
        onPaste = onPaste,
        onEnterReorderMode = { onReorderModeChanged(true) },
        onAddCustomKey = { customKey ->
            // Add to last row and persist
            val newRows = layout.rows.toMutableList()
            if (newRows.isNotEmpty()) {
                val targetRow = newRows.last().toMutableList()
                targetRow.add(customKey)
                newRows[newRows.lastIndex] = targetRow
            }
            onToolbarLayoutChanged(ToolbarLayout(newRows))
        },
        snippets = allSnippets,
        onSnippetTap = { snippet ->
            if (snippet.send == "PASTE") {
                val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
                if (text != null) {
                    if (bracketPasteMode) {
                        onSendBytes(("\u001b[200~$text\u001b[201~").toByteArray())
                    } else {
                        onSendBytes(text.toByteArray())
                    }
                }
            } else {
                onSendBytes(snippet.send.toByteArray())
            }
        },
        allowStandardKeyboard = allowStandardKeyboard,
        onToggleStandardKeyboard = onToggleStandardKeyboard,
        rawKeyboardMode = rawKeyboardMode,
        onToggleRawKeyboard = onToggleRawKeyboard,
        onAddSnippet = { snippet ->
            val newRows = layout.rows.toMutableList()
            if (newRows.isNotEmpty()) {
                val targetRow = newRows.last().toMutableList()
                targetRow.add(snippet)
                newRows[newRows.lastIndex] = targetRow
            }
            onToolbarLayoutChanged(ToolbarLayout(newRows))
        },
        onDeleteSnippet = { snippet ->
            val newRows = layout.rows.map { row -> row.filter { it != snippet } }
            onToolbarLayoutChanged(ToolbarLayout(newRows))
        },
        onAttachTap = onAttachTap,
    )

    CompositionLocalProvider(
        LocalToolbarCallbacks provides callbacks,
        LocalToolbarMinKeyWidth provides minKeyWidth,
    ) {
        Surface(
            tonalElevation = if (reorderMode) 4.dp else 2.dp,
            modifier = modifier.pointerInput(reorderMode) {
                if (reorderMode) return@pointerInput
                detectVerticalDragGestures { _, dragAmount ->
                    val window = (view.context as? android.app.Activity)?.window
                        ?: return@detectVerticalDragGestures
                    val controller = WindowCompat.getInsetsController(window, view)
                    if (dragAmount > 15f) {
                        controller.hide(WindowInsetsCompat.Type.ime())
                    } else if (dragAmount < -15f) {
                        focusRequester.requestFocus()
                        controller.show(WindowInsetsCompat.Type.ime())
                    }
                }
            },
        ) {
            if (reorderMode) {
                ReorderToolbarContent(
                    layout = layout,
                    onSave = onToolbarLayoutChanged,
                    onDone = { onReorderModeChanged(false) },
                    onOpenSettings = onOpenSettings,
                    showVncIcon = onVncTap != null,
                )
            } else if (selectionActive && selectionController != null) {
                Column {
                    if (layout.rows.size >= 2) {
                        ToolbarRow(
                            items = layout.row1,
                            focusRequester = focusRequester,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            shiftActive = shiftActive,
                            imeVisible = imeVisible,
                            view = view,
                            onVncTap = onVncTap,
                            vncLoading = vncLoading,
                        )
                    }
                    selectionContent?.invoke()
                }
            } else if (layout.rows.size >= 2 && navBlockMode == sh.haven.core.data.preferences.NavBlockMode.ALIGNED) {
                AlignedToolbarContent(
                    layout = layout,
                    focusRequester = focusRequester,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    shiftActive = shiftActive,
                    imeVisible = imeVisible,
                    view = view,
                    onVncTap = onVncTap,
                    vncLoading = vncLoading,
                )
            } else {
                Column {
                    for (row in layout.rows) {
                        if (row.isNotEmpty()) {
                            ToolbarRow(
                                items = row,
                                focusRequester = focusRequester,
                                ctrlActive = ctrlActive,
                                altActive = altActive,
                                shiftActive = shiftActive,
                                imeVisible = imeVisible,
                                view = view,
                                onVncTap = onVncTap,
                                vncLoading = vncLoading,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the two-row toolbar with an aligned navigation block.
 *
 * Layout: [left keys] [nav grid] [symbols]
 * Both rows scroll together so the nav block stays vertically aligned.
 */
@Composable
private fun AlignedToolbarContent(
    layout: ToolbarLayout,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onVncTap: (() -> Unit)?,
    vncLoading: Boolean = false,
) {
    val cb = LocalToolbarCallbacks.current
    // Split each row into: left (non-nav), right (non-nav after nav keys)
    val (row1Left, row1Right) = splitAroundNav(layout.row1)
    val (row2Left, row2Right) = splitAroundNav(layout.row2)

    // Pin the leading icon keys to a fixed 2-column grid so the Attach
    // (row 1) and Voice/secure-keyboard (row 2) icons always stack at
    // column 2 — col 1 holds the keyboard toggle (row 1) / VNC-desktop
    // (row 2), col 2 holds Attach / Voice. Placeholders keep the columns
    // aligned even when one leading icon is absent (e.g. no VNC on a
    // plain SSH profile), which previously slid Voice under column 1.
    fun ToolbarItem.isKey(k: ToolbarKey) = this is ToolbarItem.BuiltIn && this.key == k
    val r1Keyboard = row1Left.firstOrNull { it.isKey(ToolbarKey.KEYBOARD) }
    val r1Attach = row1Left.firstOrNull { it.isKey(ToolbarKey.ATTACH) }
    val r1Rest = row1Left.filterNot { it.isKey(ToolbarKey.KEYBOARD) || it.isKey(ToolbarKey.ATTACH) }
    val r2Voice = row2Left.firstOrNull { it.isKey(ToolbarKey.VOICE_KEYBOARD) }
    val r2Rest = row2Left.filterNot { it.isKey(ToolbarKey.VOICE_KEYBOARD) }

    // Collect which nav keys are present across all rows
    val presentNavKeys = layout.rows.flatten()
        .filterIsInstance<ToolbarItem.BuiltIn>()
        .filter { it.key in NAV_KEYS }
        .map { it.key }
        .toSet()

    // If no nav keys present, fall back to simple rows
    if (presentNavKeys.isEmpty()) {
        Column {
            ToolbarRow(layout.row1, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onVncTap, vncLoading)
            ToolbarRow(layout.row2, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view, onVncTap, vncLoading)
        }
        return
    }

    // All three columns scroll together for alignment
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        // Left keys, column-aligned: each column stacks its row-1 key over its
        // row-2 key in a Column sized to the wider of the two (IntrinsicSize.Max),
        // so the two rows line up in tidy columns at minimum width — no 58dp
        // pills, no misaligned rows (#184 follow-up).
        @Composable
        fun KeyCell(content: (@Composable () -> Unit)?) {
            // propagateMinConstraints stretches the key to the column width
            // (the wider of the two stacked keys), so both keys sharing a column
            // are the same width — not just centred in it (#184 follow-up).
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                contentAlignment = Alignment.Center,
                propagateMinConstraints = true,
            ) {
                if (content != null) content() else Spacer(Modifier.size(32.dp))
            }
        }
        @Composable
        fun KeyColumn(top: (@Composable () -> Unit)?, bottom: (@Composable () -> Unit)?) {
            Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                KeyCell(top)
                KeyCell(bottom)
            }
        }
        fun itemRenderer(item: ToolbarItem?): (@Composable () -> Unit)? = item?.let {
            { RenderItem(it, focusRequester, ctrlActive, altActive, shiftActive, imeVisible, view) }
        }
        // Nav (cursor) keys rendered through the same column grid as the rest,
        // so the cursor block lines up vertically with the other rows instead of
        // sitting in its own fixed-cell grid. Arrows/keys dispatch via
        // onDispatchKey so libvterm applies DECCKM (vim/mutt). (#184 follow-up)
        fun navRenderer(key: ToolbarKey): (@Composable () -> Unit)? {
            if (key !in presentNavKeys) return null
            return {
                val cb = LocalToolbarCallbacks.current
                when (key) {
                    ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("←") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
                    ToolbarKey.ARROW_UP -> ToolbarArrowButton("↑") { cb.onDispatchKey(0, VTERM_KEY_UP) }
                    ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("↓") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
                    ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("→") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
                    ToolbarKey.HOME -> ToolbarTextButton("Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
                    ToolbarKey.END -> ToolbarTextButton("End") { cb.onDispatchKey(0, VTERM_KEY_END) }
                    ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
                    ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
                    else -> {}
                }
            }
        }

        // Col 0: keyboard toggle (top) / VNC-desktop icon (bottom).
        KeyColumn(
            top = itemRenderer(r1Keyboard),
            bottom = if (onVncTap != null) {
                {
                    if (vncLoading) {
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        ToolbarIconButton(Icons.Filled.DesktopWindows, stringResource(R.string.toolbar_vnc_desktop), onVncTap)
                    }
                }
            } else {
                null
            },
        )
        // Col 1: Attach (top) / Voice toggle (bottom).
        KeyColumn(top = itemRenderer(r1Attach), bottom = itemRenderer(r2Voice))
        // Remaining columns: row-1 key over row-2 key, paired by position.
        val restColumns = maxOf(r1Rest.size, r2Rest.size)
        for (i in 0 until restColumns) {
            KeyColumn(top = itemRenderer(r1Rest.getOrNull(i)), bottom = itemRenderer(r2Rest.getOrNull(i)))
        }

        // Nav (cursor) block — same KeyColumn grid as the keys above, so the two
        // cursor rows line up with the rest. Columns: Home/←, ↑/↓, End/→, PgUp/PgDn.
        for (i in NAV_GRID_TOP.indices) {
            val topKey = NAV_GRID_TOP[i]
            val bottomKey = NAV_GRID_BOTTOM[i]
            if (topKey in presentNavKeys || bottomKey in presentNavKeys) {
                KeyColumn(top = navRenderer(topKey), bottom = navRenderer(bottomKey))
            }
        }
        // Right keys (symbols) — same column grid, so they align with the rest
        // even when present only on row 2.
        val rightColumns = maxOf(row1Right.size, row2Right.size)
        for (i in 0 until rightColumns) {
            KeyColumn(top = itemRenderer(row1Right.getOrNull(i)), bottom = itemRenderer(row2Right.getOrNull(i)))
        }
        Column(modifier = Modifier.align(Alignment.Bottom)) {
            AddKeyButton()
            ReorderEditButton()
        }
    }
}

/** A single toolbar key row with standard padding and alignment. */
@Composable
private fun KeyRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Split a row's items into (before nav keys, after nav keys). Nav keys themselves are excluded. */
private fun splitAroundNav(row: List<ToolbarItem>): Pair<List<ToolbarItem>, List<ToolbarItem>> {
    val firstNavIdx = row.indexOfFirst { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    val lastNavIdx = row.indexOfLast { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    if (firstNavIdx == -1) return row to emptyList()
    val left = row.subList(0, firstNavIdx)
    val right = if (lastNavIdx + 1 < row.size) row.subList(lastNavIdx + 1, row.size) else emptyList()
    return left to right
}

/** Render a nav-block key with fixed cell width. */
@Composable
private fun NavBuiltInKey(
    key: ToolbarKey,
    shiftActive: Boolean,
) {
    val cb = LocalToolbarCallbacks.current
    // Arrow and nav keys go through dispatchKey so libvterm applies
    // DECCKM (application cursor mode) — needed for Mutt, vim, etc.
    when (key) {
        ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("\u2190") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> ToolbarArrowButton("\u2191") { cb.onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("\u2193") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("\u2192") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> ToolbarTextButton("Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarTextButton("End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        else -> Spacer(Modifier.width(NAV_CELL_WIDTH))
    }
}

// VTermKey constants (from libvterm/include/vterm_keycodes.h)
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_INS = 9
private const val VTERM_KEY_DEL = 10
private const val VTERM_KEY_HOME = 11
private const val VTERM_KEY_END = 12
private const val VTERM_KEY_PAGEUP = 13
private const val VTERM_KEY_PAGEDOWN = 14
private const val VTERM_KEY_FUNCTION_0 = 256

/** Render any toolbar item (non-nav keys in the left/right sections). */
@Composable
private fun RenderItem(
    item: ToolbarItem,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
) {
    val cb = LocalToolbarCallbacks.current
    when (item) {
        is ToolbarItem.BuiltIn -> BuiltInKey(
            key = item.key,
            focusRequester = focusRequester,
            ctrlActive = ctrlActive,
            altActive = altActive,
            shiftActive = shiftActive,
            imeVisible = imeVisible,
            view = view,
        )
        is ToolbarItem.Custom -> {
            if (item.send == "PASTE") {
                SymbolButton(item.label) {
                    val text = cb.clipboardManager?.primaryClip
                        ?.getItemAt(0)?.text?.toString()
                    if (text != null) {
                        if (cb.bracketPasteMode) {
                            cb.onSendBytes(("\u001b[200~$text\u001b[201~").toByteArray())
                        } else {
                            cb.onSendBytes(text.toByteArray())
                        }
                    }
                }
            } else {
                SymbolButton(item.label) {
                    val bytes = item.send.toByteArray()
                    if (ctrlActive || altActive) {
                        if (item.send.length == 1) {
                            sendChar(item.send[0], ctrlActive, altActive, cb.onSendBytes)
                        } else {
                            cb.onSendBytes(bytes)
                        }
                        if (ctrlActive) cb.onToggleCtrl()
                        if (altActive) cb.onToggleAlt()
                    } else {
                        cb.onSendBytes(bytes)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarRow(
    items: List<ToolbarItem>,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
    onVncTap: (() -> Unit)? = null,
    vncLoading: Boolean = false,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            RenderItem(item, focusRequester, ctrlActive, altActive,
                shiftActive, imeVisible, view)
            if (item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD && onVncTap != null) {
                if (vncLoading) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    ToolbarIconButton(Icons.Filled.DesktopWindows, stringResource(R.string.toolbar_vnc_desktop), onVncTap)
                }
            }
        }
        AddKeyButton()
        ReorderEditButton()
    }
}

@Composable
private fun AddKeyButton() {
    val cb = LocalToolbarCallbacks.current
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AddCustomKeyDialog(
            onDismiss = { showDialog = false },
            onSave = { customKey ->
                showDialog = false
                cb.onAddCustomKey(customKey)
            },
        )
    }

    FilledTonalButton(
        onClick = { showDialog = true },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.toolbar_add_custom_key),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BuiltInKey(
    key: ToolbarKey,
    focusRequester: FocusRequester,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    imeVisible: Boolean,
    view: android.view.View,
) {
    val cb = LocalToolbarCallbacks.current
    when (key) {
        @OptIn(ExperimentalFoundationApi::class)
        ToolbarKey.KEYBOARD -> {
            // Bespoke (not the shared primitive) because it needs combinedClickable
            // for the long-press → reorder gesture, but styled to match the
            // primitive so it sits flush with the other keys.
            Surface(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .height(32.dp)
                    .widthIn(min = LocalToolbarMinKeyWidth.current)
                    .combinedClickable(
                        onClick = {
                            val window = (view.context as? Activity)?.window ?: return@combinedClickable
                            val controller = WindowCompat.getInsetsController(window, view)
                            if (imeVisible) {
                                controller.hide(WindowInsetsCompat.Type.ime())
                            } else {
                                focusRequester.requestFocus()
                                controller.show(WindowInsetsCompat.Type.ime())
                            }
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            cb.onEnterReorderMode()
                        },
                    ),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ToolbarKeyIcon(Icons.Filled.Keyboard, stringResource(R.string.toolbar_toggle_keyboard))
                }
            }
        }
        ToolbarKey.ESC_KEY -> ToolbarTextButton("Esc") { cb.onSendBytes(KEY_ESC) }
        // Rendered with the larger, bold glyph style the arrow keys use (16sp
        // vs the 11sp text buttons) so the return symbol is clearly legible (#184).
        ToolbarKey.ENTER_KEY -> ToolbarArrowButton("⏎") { cb.onSendBytes(KEY_ENTER) }
        ToolbarKey.TAB_KEY -> ToolbarTextButton("Tab") {
            if (shiftActive) {
                cb.onSendBytes(KEY_SHIFT_TAB)
                cb.onShiftUsed()
            } else {
                cb.onSendBytes(KEY_TAB)
            }
        }
        ToolbarKey.VOICE_KEYBOARD -> ToolbarIconToggleButton(
            // Padlock icon: closed when Secure (default — no suggestions, no
            // autocorrect, no learning, no extract UI); open when Standard
            // mode is active (voice + suggestions + autocorrect all enabled).
            // The previous "Voice" text label was misleading because voice
            // input is just one of the things the toggle unlocks. (#115)
            icon = if (cb.allowStandardKeyboard) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = if (cb.allowStandardKeyboard)
                "Standard keyboard (voice, suggestions, autocorrect on)"
            else
                "Secure keyboard (no suggestions, no autocorrect)",
            active = cb.allowStandardKeyboard,
            onClick = cb.onToggleStandardKeyboard,
        )
        ToolbarKey.RAW_KEYBOARD -> ToolbarToggleButton(
            label = "Raw",
            active = cb.rawKeyboardMode,
            onClick = cb.onToggleRawKeyboard,
        )
        ToolbarKey.PASTE -> ToolbarTextButton("Paste") {
            val text = cb.clipboardManager?.primaryClip
                ?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                if (cb.bracketPasteMode) {
                    cb.onPaste("\u001b[200~$text\u001b[201~")
                } else {
                    cb.onPaste(text)
                }
            }
        }
        ToolbarKey.SNIPPETS -> {
            var showSheet by remember { mutableStateOf(false) }
            ToolbarTextButton("\u2702") { showSheet = true }
            if (showSheet) {
                SnippetsBottomSheet(
                    snippets = cb.snippets,
                    onDismiss = { showSheet = false },
                    onSnippetTap = { snippet ->
                        cb.onSnippetTap(snippet)
                        showSheet = false
                    },
                    onAddSnippet = cb.onAddSnippet,
                    onDeleteSnippet = cb.onDeleteSnippet,
                )
            }
        }
        ToolbarKey.ATTACH -> ToolbarIconButton(
            icon = Icons.Filled.AttachFile,
            description = "Attach file to send to remote",
            onClick = cb.onAttachTap,
        )
        ToolbarKey.SHIFT -> ToolbarToggleButton("Shift", shiftActive, onClick = cb.onToggleShift)
        ToolbarKey.CTRL -> ToolbarToggleButton("Ctrl", ctrlActive, onClick = cb.onToggleCtrl)
        ToolbarKey.ALT -> ToolbarToggleButton("Alt", altActive, onClick = cb.onToggleAlt)
        ToolbarKey.ALTGR -> {
            val altGrActive = ctrlActive && altActive
            ToolbarToggleButton("AltGr", altGrActive) {
                if (altGrActive) {
                    cb.onToggleCtrl()
                    cb.onToggleAlt()
                } else {
                    if (!ctrlActive) cb.onToggleCtrl()
                    if (!altActive) cb.onToggleAlt()
                }
            }
        }
        // Nav keys — routed through dispatchKey for DECCKM support
        ToolbarKey.ARROW_LEFT -> ToolbarArrowButton("\u2190") { cb.onDispatchKey(0, VTERM_KEY_LEFT) }
        ToolbarKey.ARROW_UP -> ToolbarArrowButton("\u2191") { cb.onDispatchKey(0, VTERM_KEY_UP) }
        ToolbarKey.ARROW_DOWN -> ToolbarArrowButton("\u2193") { cb.onDispatchKey(0, VTERM_KEY_DOWN) }
        ToolbarKey.ARROW_RIGHT -> ToolbarArrowButton("\u2192") { cb.onDispatchKey(0, VTERM_KEY_RIGHT) }
        ToolbarKey.HOME -> ToolbarTextButton("Home") { cb.onDispatchKey(0, VTERM_KEY_HOME) }
        ToolbarKey.END -> ToolbarTextButton("End") { cb.onDispatchKey(0, VTERM_KEY_END) }
        ToolbarKey.PGUP -> ToolbarTextButton("PgUp") { cb.onDispatchKey(0, VTERM_KEY_PAGEUP) }
        ToolbarKey.PGDN -> ToolbarTextButton("PgDn") { cb.onDispatchKey(0, VTERM_KEY_PAGEDOWN) }
        ToolbarKey.INSERT -> ToolbarTextButton("Ins") { cb.onDispatchKey(0, VTERM_KEY_INS) }
        ToolbarKey.DELETE -> ToolbarTextButton("Del") { cb.onDispatchKey(0, VTERM_KEY_DEL) }
        // F-keys — routed through dispatchKey so libvterm generates correct sequences
        ToolbarKey.F1 -> ToolbarTextButton("F1") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 1) }
        ToolbarKey.F2 -> ToolbarTextButton("F2") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 2) }
        ToolbarKey.F3 -> ToolbarTextButton("F3") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 3) }
        ToolbarKey.F4 -> ToolbarTextButton("F4") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 4) }
        ToolbarKey.F5 -> ToolbarTextButton("F5") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 5) }
        ToolbarKey.F6 -> ToolbarTextButton("F6") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 6) }
        ToolbarKey.F7 -> ToolbarTextButton("F7") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 7) }
        ToolbarKey.F8 -> ToolbarTextButton("F8") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 8) }
        ToolbarKey.F9 -> ToolbarTextButton("F9") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 9) }
        ToolbarKey.F10 -> ToolbarTextButton("F10") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 10) }
        ToolbarKey.F11 -> ToolbarTextButton("F11") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 11) }
        ToolbarKey.F12 -> ToolbarTextButton("F12") { cb.onDispatchKey(0, VTERM_KEY_FUNCTION_0 + 12) }
        else -> {
            val ch = key.char ?: return
            SymbolButton(key.label) {
                sendChar(ch, ctrlActive, altActive, cb.onSendBytes)
                if (ctrlActive) cb.onToggleCtrl()
                if (altActive) cb.onToggleAlt()
            }
        }
    }
}

private fun sendChar(
    char: Char,
    ctrl: Boolean,
    alt: Boolean,
    onSendBytes: (ByteArray) -> Unit,
) {
    val byte = if (ctrl && char.code in 0x40..0x7F) {
        byteArrayOf((char.code and 0x1F).toByte())
    } else {
        char.toString().toByteArray()
    }

    if (alt) {
        onSendBytes(byteArrayOf(0x1b) + byte)
    } else {
        onSendBytes(byte)
    }
}

// --- Nav block buttons (fixed width) ---

/** Nav cell wrapper — ensures buttons and spacers occupy the exact same width. */
@Composable
private fun NavCell(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.width(NAV_CELL_WIDTH).height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// --- Key repeat ---

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L

/**
 * FilledTonalButton with key repeat. Uses Android MotionEvent interop to detect
 * press/release, bypassing Compose's gesture system (which the horizontalScroll
 * parent intercepts). Consumes touch events and handles both tap and repeat.
 */
/**
 * The single toolbar-key primitive every key renders through, so all keys share
 * one height, padding, shape, min-width, selected-styling and repeat behaviour
 * (the previous per-key composables had drifted to 30-vs-32dp, 6-vs-8dp,
 * 58dp Material minimums, etc. — the root of a run of alignment/width bugs).
 *
 * Backed by a tonal [Surface] (not FilledTonalButton, which forces a ~58dp
 * minimum width). Width = content + 8dp padding, floored at [LocalToolbarMinKeyWidth]
 * (the user's "minimum key width" setting). [selected] paints the active toggle
 * state; [repeating] enables press-and-hold key repeat via the Android
 * MotionEvent interop (Compose gestures get stolen by the scrolling parent).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToolbarKeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    repeating: Boolean = false,
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    var didRepeat by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed, repeating) {
        if (isPressed && repeating) {
            didRepeat = false
            delay(REPEAT_DELAY_MS)
            didRepeat = true
            while (true) {
                onClick()
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    Surface(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .height(32.dp)
            .widthIn(min = LocalToolbarMinKeyWidth.current)
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        true // consume to receive UP
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!didRepeat) onClick() // single tap
                        isPressed = false
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        true
                    }
                    else -> false
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** Uniform key label. [glyph] = the larger bold style for arrows/symbols/⏎. */
@Composable
private fun ToolbarKeyText(label: String, glyph: Boolean = false) {
    Text(
        label,
        fontSize = if (glyph) 16.sp else 12.sp,
        lineHeight = if (glyph) 16.sp else 12.sp,
        fontWeight = if (glyph) androidx.compose.ui.text.font.FontWeight.Bold else null,
    )
}

/** Uniform key icon (18dp) for icon keys. */
@Composable
private fun ToolbarKeyIcon(icon: ImageVector, contentDescription: String? = null) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
}

/**
 * The icon for keys drawn as an icon rather than text. Used by both the live
 * render and the reorder-mode chips so a key looks identical in either mode.
 * Voice is represented by the secure-state lock. Returns null for text keys.
 */
private fun keyIcon(key: ToolbarKey): ImageVector? = when (key) {
    ToolbarKey.KEYBOARD -> Icons.Filled.Keyboard
    ToolbarKey.ATTACH -> Icons.Filled.AttachFile
    ToolbarKey.VOICE_KEYBOARD -> Icons.Filled.Lock
    else -> null
}

// --- Standard buttons (variable width) ---

@Composable
private fun ToolbarArrowButton(label: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label, glyph = true)
    }
}

@Composable
private fun ToolbarTextButton(label: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label)
    }
}

@Composable
private fun ToolbarToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick, selected = active) {
        ToolbarKeyText(label)
    }
}

/**
 * Icon-based toggle button. Bare icon (no surrounding pill) so it sits
 * flush with the other icon-only keys like Attach (#115 follow-up). The
 * on/off state is communicated via tint: primary when active, the usual
 * onSurfaceVariant when inactive.
 */
@Composable
private fun ToolbarIconToggleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    ToolbarKeyButton(onClick = onClick, selected = active) {
        ToolbarKeyIcon(icon, contentDescription)
    }
}

@Composable
private fun SymbolButton(label: String, onClick: () -> Unit) {
    // Single-character keys (built-in symbols like | ~) use the larger bold
    // glyph; multi-character labels (custom keys like "⇧Tab", "mcp") use the
    // normal text weight so they match the built-in word keys (Esc/Tab/…).
    ToolbarKeyButton(onClick = onClick, repeating = true) {
        ToolbarKeyText(label, glyph = label.length <= 1)
    }
}

/** Edit button at the end of the toolbar — tapping enters reorder mode. */
@Composable
private fun ReorderEditButton() {
    val cb = LocalToolbarCallbacks.current
    FilledTonalButton(
        onClick = { cb.onEnterReorderMode() },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.toolbar_reorder_keys),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    ToolbarKeyButton(onClick = onClick) {
        ToolbarKeyIcon(icon, description)
    }
}

// --- Toolbar reorder mode ---

/** Return the index range of contiguous nav keys in a row, or null if none. */
private fun navBounds(row: List<ToolbarItem>): IntRange? {
    val first = row.indexOfFirst { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    if (first == -1) return null
    val last = row.indexOfLast { it is ToolbarItem.BuiltIn && it.key in NAV_KEYS }
    return first..last
}

@Composable
private fun ReorderToolbarContent(
    layout: ToolbarLayout,
    onSave: (ToolbarLayout) -> Unit,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit = {},
    showVncIcon: Boolean = false,
) {
    val rows = remember(layout) {
        // Pin the leading icon keys to match the live render's fixed 2-column
        // grid (col 1 = keyboard / VNC, col 2 = Attach / Voice). In edit mode the
        // done-✓ button replaces KEYBOARD *in place* and the VNC icon is prepended
        // to row 2 outside the data, so the data order must be:
        //   row 1: [KEYBOARD, ATTACH, …]  -> [done, attach, …]
        //   row 2: [VOICE,    …]          -> [VNC,  voice,  …]
        // so Attach (row 1) and Voice (row 2) both land at column 2 and align.
        fun MutableList<ToolbarItem>.pinToFront(key: ToolbarKey, target: Int) {
            val idx = indexOfFirst { it is ToolbarItem.BuiltIn && it.key == key }
            if (idx >= 0 && idx != target) add(target.coerceAtMost(size), removeAt(idx))
        }
        layout.rows.mapIndexed { i, row ->
            val list = row.toMutableList()
            when (i) {
                0 -> {
                    list.pinToFront(ToolbarKey.KEYBOARD, 0)
                    list.pinToFront(ToolbarKey.ATTACH, 1)
                }
                1 -> list.pinToFront(ToolbarKey.VOICE_KEYBOARD, 0)
            }
            list.toMutableStateList()
        }
    }

    fun saveAndExit() {
        onSave(ToolbarLayout(rows.map { it.toList() }))
        onDone()
    }

    BackHandler { saveAndExit() }

    var showAddKeyDialog by remember { mutableStateOf(false) }

    if (showAddKeyDialog) {
        AddCustomKeyDialog(
            onDismiss = { showAddKeyDialog = false },
            onSave = { customKey ->
                showAddKeyDialog = false
                // Add to row 2 if available, else row 1 (at the end)
                val targetRow = rows.getOrElse(1) { rows[0] }
                targetRow.add(customKey)
            },
        )
    }

    val row1 = rows.getOrNull(0) ?: return
    val row2 = rows.getOrNull(1)
    val nav1 = navBounds(row1)
    val nav2 = if (row2 != null) navBounds(row2) else null

    // If no nav keys or single row, fall back to flat rows
    if (nav1 == null && nav2 == null || row2 == null) {
        Column {
            rows.forEachIndexed { i, items ->
                DraggableSegment(
                    items = items,
                    range = 0 until items.size,
                    showDoneButton = i == 0,
                    onDone = ::saveAndExit,
                )
            }
        }
        return
    }

    // Aligned three-column layout: [left keys] [nav block] [right keys]
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        // Helper: transfer an item across the nav block
        fun transferRight(row: MutableList<ToolbarItem>, fromIndex: Int) {
            val item = row.removeAt(fromIndex)
            val insertAt = navBounds(row)?.let { it.last + 1 } ?: row.size
            row.add(insertAt, item)
        }
        fun transferLeft(row: MutableList<ToolbarItem>, fromIndex: Int) {
            val item = row.removeAt(fromIndex)
            val insertAt = navBounds(row)?.first ?: 0
            row.add(insertAt, item)
        }

        /** Move item from one row to the same segment in the other row. */
        fun transferBetweenRows(
            sourceRow: MutableList<ToolbarItem>,
            targetRow: MutableList<ToolbarItem>,
            fromIndex: Int,
            isLeftSegment: Boolean,
        ) {
            val item = sourceRow.removeAt(fromIndex)
            val targetNav = navBounds(targetRow)
            val insertAt = if (isLeftSegment) {
                targetNav?.first ?: targetRow.size
            } else {
                targetRow.size
            }
            targetRow.add(insertAt, item)
        }

        // Left column — IntrinsicSize.Max aligns both rows
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            DraggableSegment(
                items = row1,
                range = 0 until (nav1?.first ?: row1.size),
                showDoneButton = true,
                onDone = ::saveAndExit,
                onTransferRight = if (nav1 != null) { idx -> transferRight(row1, idx) } else null,
                onTransferToOtherRow = if (row2 != null) { idx ->
                    transferBetweenRows(row1, row2, idx, isLeftSegment = true)
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (row2 != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showVncIcon) {
                        Icon(
                            Icons.Filled.DesktopWindows,
                            contentDescription = stringResource(R.string.toolbar_vnc_desktop),
                            modifier = Modifier
                                .size(32.dp)
                                .padding(7.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DraggableSegment(
                        items = row2,
                        range = 0 until (nav2?.first ?: row2.size),
                        onTransferRight = if (nav2 != null) { idx -> transferRight(row2, idx) } else null,
                        onTransferToOtherRow = { idx ->
                            transferBetweenRows(row2, row1, idx, isLeftSegment = true)
                        },
                    )
                }
            }
        }

        // Nav block — fixed-width cells matching live mode grid
        val presentNavKeys = rows.flatten()
            .filterIsInstance<ToolbarItem.BuiltIn>()
            .filter { it.key in NAV_KEYS }
            .map { it.key }
            .toSet()

        Column(
            modifier = Modifier
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            KeyRow {
                for (key in NAV_GRID_TOP) {
                    if (key in presentNavKeys) ReorderNavCell(key) else NavCell {}
                }
            }
            KeyRow {
                for (key in NAV_GRID_BOTTOM) {
                    if (key in presentNavKeys) ReorderNavCell(key) else NavCell {}
                }
            }
        }

        // Right column
        val r1RightStart = (nav1?.last?.plus(1)) ?: row1.size
        val r2RightStart = if (row2 != null) (nav2?.last?.plus(1)) ?: row2.size else 0
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            DraggableSegment(
                items = row1,
                range = r1RightStart until row1.size,
                onTransferLeft = if (nav1 != null) { idx -> transferLeft(row1, idx) } else null,
                onTransferToOtherRow = if (row2 != null) { idx ->
                    transferBetweenRows(row1, row2, idx, isLeftSegment = false)
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (row2 != null) {
                DraggableSegment(
                    items = row2,
                    range = r2RightStart until row2.size,
                    onTransferLeft = if (nav2 != null) { idx -> transferLeft(row2, idx) } else null,
                    onTransferToOtherRow = { idx ->
                        transferBetweenRows(row2, row1, idx, isLeftSegment = false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Action buttons: add custom key + open full settings
        Column(
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 4.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            FilledTonalButton(
                onClick = { showAddKeyDialog = true },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.toolbar_add_custom_key),
                    modifier = Modifier.size(18.dp),
                )
            }
            FilledTonalButton(
                onClick = { saveAndExit(); onOpenSettings() },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.toolbar_settings),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A draggable row of items from a sub-range of a row's item list. */
@Composable
private fun DraggableSegment(
    items: MutableList<ToolbarItem>,
    range: IntRange,
    showDoneButton: Boolean = false,
    onDone: () -> Unit = {},
    onTransferRight: ((Int) -> Unit)? = null,
    onTransferLeft: ((Int) -> Unit)? = null,
    onTransferToOtherRow: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (range.isEmpty()) {
        // Empty segment — still need height for alignment
        Spacer(modifier.height(34.dp))
        return
    }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemWidths = remember { mutableStateMapOf<Int, Float>() }
    val itemLeftEdges = remember { mutableStateMapOf<Int, Float>() }
    val view = LocalView.current
    val transferThreshold = 60f // pixels — ~22dp, enough to indicate intent

    fun checkSwaps() {
        if (draggedIndex < 0) return
        val left = itemLeftEdges[draggedIndex] ?: return
        val width = itemWidths[draggedIndex] ?: return
        val center = left + width / 2 + dragOffset
        if (draggedIndex < range.last) {
            val nL = itemLeftEdges[draggedIndex + 1]
            val nW = itemWidths[draggedIndex + 1]
            if (nL != null && nW != null && center > nL + nW / 2) {
                val from = draggedIndex
                items.add(from + 1, items.removeAt(from))
                draggedIndex = from + 1
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        if (draggedIndex > range.first) {
            val pL = itemLeftEdges[draggedIndex - 1]
            val pW = itemWidths[draggedIndex - 1]
            if (pL != null && pW != null && center < pL + pW / 2) {
                val from = draggedIndex
                items.add(from - 1, items.removeAt(from))
                draggedIndex = from - 1
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        // Cross-nav transfer: drag past segment boundary
        if (onTransferRight != null && draggedIndex == range.last &&
            dragOffset > transferThreshold
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferRight(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
        if (onTransferLeft != null && draggedIndex == range.first &&
            dragOffset < -transferThreshold
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferLeft(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
        // Cross-row transfer: drag up or down
        if (onTransferToOtherRow != null &&
            (dragOffsetY > transferThreshold || dragOffsetY < -transferThreshold)
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTransferToOtherRow(draggedIndex)
            draggedIndex = -1
            dragOffset = 0f
            dragOffsetY = 0f
        }
    }

    Row(
        modifier = modifier
            .padding(vertical = 1.dp)
            .pointerInput(range) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hit = range.find { idx ->
                            val l = itemLeftEdges[idx] ?: return@find false
                            val w = itemWidths[idx] ?: return@find false
                            offset.x >= l && offset.x < l + w
                        } ?: -1
                        if (hit >= 0) {
                            val item = items[hit]
                            val skip = item is ToolbarItem.BuiltIn &&
                                item.key == ToolbarKey.KEYBOARD
                            if (!skip) {
                                draggedIndex = hit
                                dragOffset = 0f
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggedIndex >= 0) {
                            dragOffset += amount.x
                            dragOffsetY += amount.y
                            checkSwaps()
                        }
                    },
                    onDragEnd = { draggedIndex = -1; dragOffset = 0f; dragOffsetY = 0f },
                    onDragCancel = { draggedIndex = -1; dragOffset = 0f; dragOffsetY = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (idx in range) {
            val item = items[idx]
            val isDoneBtn = showDoneButton &&
                item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD
            if (isDoneBtn) {
                IconButton(
                    onClick = onDone,
                    modifier = Modifier
                        .size(32.dp)
                        .onGloballyPositioned { c ->
                            itemWidths[idx] = c.size.width.toFloat()
                            itemLeftEdges[idx] = c.positionInParent().x
                        },
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.toolbar_done_reordering),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { c ->
                            itemWidths[idx] = c.size.width.toFloat()
                            itemLeftEdges[idx] = c.positionInParent().x
                        }
                        .then(
                            if (idx == draggedIndex) {
                                Modifier
                                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                            } else Modifier
                        ),
                ) {
                    ReorderModeKey(item)
                }
            }
        }
    }
}

@Composable
private fun ReorderableToolbarRow(
    items: MutableList<ToolbarItem>,
    showDoneButton: Boolean = false,
    onDone: () -> Unit = {},
    navGroupShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
) {
    var dragGroupStart by remember { mutableIntStateOf(-1) }
    var dragGroupEnd by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val itemWidths = remember { mutableStateMapOf<Int, Float>() }
    val itemLeftEdges = remember { mutableStateMapOf<Int, Float>() }
    val view = LocalView.current
    val scrollState = rememberScrollState()
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableIntStateOf(0) }

    /** Find the contiguous run of nav keys containing [index], or index..index for non-nav. */
    fun navGroupAt(index: Int): IntRange {
        val item = items[index]
        if (item !is ToolbarItem.BuiltIn || item.key !in NAV_KEYS) return index..index
        var start = index
        while (start > 0) {
            val prev = items[start - 1]
            if (prev is ToolbarItem.BuiltIn && prev.key in NAV_KEYS) start-- else break
        }
        var end = index
        while (end < items.lastIndex) {
            val next = items[end + 1]
            if (next is ToolbarItem.BuiltIn && next.key in NAV_KEYS) end++ else break
        }
        return start..end
    }

    fun checkSwaps() {
        if (dragGroupStart < 0) return
        val groupLeft = itemLeftEdges[dragGroupStart] ?: return
        val groupWidth = itemWidths[dragGroupStart] ?: return
        val groupCenter = groupLeft + groupWidth / 2 + dragOffset
        // Swap with right neighbor (move it before the group)
        val rightIdx = dragGroupEnd + 1
        if (rightIdx <= items.lastIndex) {
            val nextLeft = itemLeftEdges[rightIdx]
            val nextWidth = itemWidths[rightIdx]
            if (nextLeft != null && nextWidth != null &&
                groupCenter > nextLeft + nextWidth / 2
            ) {
                val removed = items.removeAt(rightIdx)
                items.add(dragGroupStart, removed)
                dragGroupStart++
                dragGroupEnd++
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        // Swap with left neighbor (move it after the group)
        val leftIdx = dragGroupStart - 1
        if (leftIdx >= 0) {
            val prevLeft = itemLeftEdges[leftIdx]
            val prevWidth = itemWidths[leftIdx]
            if (prevLeft != null && prevWidth != null &&
                groupCenter < prevLeft + prevWidth / 2
            ) {
                val removed = items.removeAt(leftIdx)
                items.add(dragGroupEnd, removed)
                dragGroupStart--
                dragGroupEnd--
                dragOffset = 0f
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    // Auto-scroll when dragging near viewport edges
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed != 0f) {
            while (true) {
                val scrolled = scrollState.scrollBy(autoScrollSpeed)
                dragOffset += scrolled
                checkSwaps()
                delay(16)
            }
        }
    }

    Box(
        modifier = Modifier.onGloballyPositioned { viewportWidth = it.size.width },
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .pointerInput(items.size) {
                    val edgeThreshold = 40.dp.toPx()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val hitIndex = (0 until items.size).find { idx ->
                                val left = itemLeftEdges[idx] ?: return@find false
                                val width = itemWidths[idx] ?: return@find false
                                offset.x >= left && offset.x < left + width
                            } ?: -1
                            if (hitIndex >= 0) {
                                val item = items[hitIndex]
                                val isKeyboard = item is ToolbarItem.BuiltIn &&
                                    item.key == ToolbarKey.KEYBOARD
                                if (!isKeyboard) {
                                    val group = navGroupAt(hitIndex)
                                    dragGroupStart = group.first
                                    dragGroupEnd = group.last
                                    dragOffset = 0f
                                    view.performHapticFeedback(
                                        HapticFeedbackConstants.LONG_PRESS,
                                    )
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragGroupStart >= 0) {
                                dragOffset += dragAmount.x
                                checkSwaps()
                                // Auto-scroll near viewport edges
                                val fingerInViewport =
                                    change.position.x - scrollState.value
                                autoScrollSpeed = when {
                                    fingerInViewport < edgeThreshold &&
                                        scrollState.value > 0 -> -8f
                                    viewportWidth > 0 &&
                                        fingerInViewport > viewportWidth - edgeThreshold &&
                                        scrollState.value < scrollState.maxValue -> 8f
                                    else -> 0f
                                }
                            }
                        },
                        onDragEnd = {
                            dragGroupStart = -1
                            dragGroupEnd = -1
                            dragOffset = 0f
                            autoScrollSpeed = 0f
                        },
                        onDragCancel = {
                            dragGroupStart = -1
                            dragGroupEnd = -1
                            dragOffset = 0f
                            autoScrollSpeed = 0f
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var i = 0
            while (i < items.size) {
                val item = items[i]
                val isDoneButton = showDoneButton &&
                    item is ToolbarItem.BuiltIn && item.key == ToolbarKey.KEYBOARD
                val isNav = item is ToolbarItem.BuiltIn && item.key in NAV_KEYS

                if (isDoneButton) {
                    IconButton(
                        onClick = onDone,
                        modifier = Modifier
                            .size(32.dp)
                            .onGloballyPositioned { coords ->
                                itemWidths[i] = coords.size.width.toFloat()
                                itemLeftEdges[i] = coords.positionInParent().x
                            },
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.toolbar_done_reordering),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    i++
                } else if (isNav) {
                    // Find contiguous run of nav keys
                    var end = i
                    while (end + 1 < items.size) {
                        val next = items[end + 1]
                        if (next is ToolbarItem.BuiltIn && next.key in NAV_KEYS) end++
                        else break
                    }
                    val groupStart = i
                    val isBeingDragged = groupStart in dragGroupStart..dragGroupEnd
                    // Render nav group as a bordered unit
                    Row(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                itemWidths[groupStart] = coords.size.width.toFloat()
                                itemLeftEdges[groupStart] =
                                    coords.positionInParent().x
                            }
                            .then(
                                if (isBeingDragged) {
                                    Modifier
                                        .offset {
                                            IntOffset(dragOffset.roundToInt(), 0)
                                        }
                                        .zIndex(1f)
                                        .graphicsLayer {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                } else Modifier
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                navGroupShape,
                            )
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (j in groupStart..end) {
                            ReorderModeNavKey(items[j])
                        }
                    }
                    i = end + 1
                } else {
                    // Single non-nav item
                    val idx = i
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                itemWidths[idx] = coords.size.width.toFloat()
                                itemLeftEdges[idx] = coords.positionInParent().x
                            }
                            .then(
                                if (idx in dragGroupStart..dragGroupEnd) {
                                    Modifier
                                        .offset {
                                            IntOffset(dragOffset.roundToInt(), 0)
                                        }
                                        .zIndex(1f)
                                        .graphicsLayer {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                } else Modifier
                            ),
                    ) {
                        ReorderModeKey(item)
                    }
                    i++
                }
            }
        }
    }
}

@Composable
private fun ReorderModeKey(item: ToolbarItem) {
    // Icon keys show their icon here too (not a text label), so a key looks the
    // same in reorder mode as in live mode.
    val icon = (item as? ToolbarItem.BuiltIn)?.let { keyIcon(it.key) }
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .height(32.dp)
            .widthIn(min = LocalToolbarMinKeyWidth.current),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                ToolbarKeyIcon(icon, item.displayLabel)
            } else {
                ToolbarKeyText(item.displayLabel)
            }
        }
    }
}

/** Nav cell for reorder mode — fixed 44dp width matching live mode. */
@Composable
private fun ReorderNavCell(key: ToolbarKey) {
    val isArrow = key in setOf(
        ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_UP,
        ToolbarKey.ARROW_DOWN, ToolbarKey.ARROW_RIGHT,
    )
    val label = when (key) {
        ToolbarKey.ARROW_LEFT -> "\u2190"
        ToolbarKey.ARROW_UP -> "\u2191"
        ToolbarKey.ARROW_DOWN -> "\u2193"
        ToolbarKey.ARROW_RIGHT -> "\u2192"
        else -> key.label
    }
    NavCell {
        FilledTonalButton(
            onClick = {},
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                label,
                fontSize = if (isArrow) 16.sp else 11.sp,
                lineHeight = if (isArrow) 16.sp else 11.sp,
                fontWeight = if (isArrow) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else null,
            )
        }
    }
}

/** Display label for nav keys — uses arrow symbols instead of text names. */
private fun navKeyLabel(item: ToolbarItem): String {
    if (item !is ToolbarItem.BuiltIn) return item.displayLabel
    return when (item.key) {
        ToolbarKey.ARROW_LEFT -> "\u2190"
        ToolbarKey.ARROW_UP -> "\u2191"
        ToolbarKey.ARROW_DOWN -> "\u2193"
        ToolbarKey.ARROW_RIGHT -> "\u2192"
        else -> item.displayLabel
    }
}

@Composable
private fun ReorderModeNavKey(item: ToolbarItem) {
    val label = navKeyLabel(item)
    val isArrow = item is ToolbarItem.BuiltIn && item.key in setOf(
        ToolbarKey.ARROW_LEFT, ToolbarKey.ARROW_UP,
        ToolbarKey.ARROW_DOWN, ToolbarKey.ARROW_RIGHT,
    )
    FilledTonalButton(
        onClick = {},
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            fontSize = if (isArrow) 16.sp else 11.sp,
            lineHeight = if (isArrow) 16.sp else 11.sp,
            fontWeight = if (isArrow) {
                androidx.compose.ui.text.font.FontWeight.Bold
            } else null,
        )
    }
}

// --- Add custom key dialog ---

internal fun displaySendSequence(send: String): String {
    if (send == "PASTE") return "Paste clipboard"
    return send.map { ch ->
        when {
            ch.code < 0x20 -> "\\u${ch.code.toString(16).padStart(4, '0')}"
            else -> ch.toString()
        }
    }.joinToString("")
}

internal fun parseSendSequence(input: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        if (i + 1 < input.length && input[i] == '\\') {
            when (input[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'u' -> {
                    if (i + 5 < input.length) {
                        val hex = input.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(input[i]); i++ }
                    } else { sb.append(input[i]); i++ }
                }
                else -> { sb.append(input[i]); i++ }
            }
        } else { sb.append(input[i]); i++ }
    }
    return sb.toString()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddCustomKeyDialog(
    onDismiss: () -> Unit,
    onSave: (ToolbarItem.Custom) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var sendText by remember { mutableStateOf("") }
    var presetExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_custom_key_dialog_title)) },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "Presets",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false },
                    ) {
                        MACRO_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.description) },
                                onClick = {
                                    label = preset.label
                                    sendText = displaySendSequence(preset.send)
                                    presetExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.toolbar_custom_key_label)) },
                    placeholder = { Text("e.g. ^C, Paste") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    label = { Text(stringResource(R.string.toolbar_custom_key_sequence)) },
                    placeholder = { Text("e.g. \\u0003") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Use \\u001b for Escape, \\u0003 for Ctrl+C, \\n for Enter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseSendSequence(sendText)
                    onSave(ToolbarItem.Custom(label.trim(), parsed))
                },
                enabled = label.isNotBlank() && sendText.isNotBlank(),
            ) {
                Text(stringResource(R.string.toolbar_custom_key_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolbar_custom_key_cancel)) }
        },
    )
}
