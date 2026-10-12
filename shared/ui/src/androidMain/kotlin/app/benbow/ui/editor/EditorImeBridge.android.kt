package app.benbow.ui.editor

import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Compose hands the IME the *displayed* text, where paragraph breaks are zero-width glyphs. The
 * keyboard would then miss paragraph starts (no auto-capitalization) and could treat a glyph as part
 * of a word. This bridge shows the IME the real text; lengths match, so offsets stay valid.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun EditorImeBridge(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = RealTextInterceptor, content = content)
}

@OptIn(ExperimentalComposeUiApi::class)
private object RealTextInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val wrapped = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.useRealInitialText()
                return RealTextInputConnection(connection)
            }
        }
        nextHandler.startInputMethod(wrapped)
    }
}

private class RealTextInputConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = super.getTextBeforeCursor(n, flags)?.toRealText()

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = super.getTextAfterCursor(n, flags)?.toRealText()

    override fun getSelectedText(flags: Int): CharSequence? = super.getSelectedText(flags)?.toRealText()

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        super.getExtractedText(request, flags)?.apply { text = text?.toRealText() }

    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        super.getSurroundingText(beforeLength, afterLength, flags)?.let {
            SurroundingText(it.text.toRealText(), it.selectionStart, it.selectionEnd, it.offset)
        }

    override fun getCursorCapsMode(reqModes: Int): Int {
        val before = getTextBeforeCursor(CAPS_CONTEXT, 0) ?: return super.getCursorCapsMode(reqModes)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }
}

private fun EditorInfo.useRealInitialText() {
    val before = getInitialTextBeforeCursor(INITIAL_CONTEXT, 0) ?: return
    val after = getInitialTextAfterCursor(INITIAL_CONTEXT, 0) ?: return
    val selected = getInitialSelectedText(0) ?: ""
    val realBefore = before.toRealText()
    setInitialSurroundingSubText(realBefore + selected.toRealText() + after.toRealText(), initialSelStart - before.length)
    if (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) {
        initialCapsMode = TextUtils.getCapsMode(realBefore, realBefore.length, inputType)
    }
}

/** Same length as the input except a trailing [TAIL_GLYPH], which only ever sits at the very end. */
private fun CharSequence.toRealText(): String =
    toString().replace(PARAGRAPH_BREAK_GLYPH, '\n').removeSuffix(TAIL_GLYPH.toString())

private const val CAPS_CONTEXT = 256
private const val INITIAL_CONTEXT = 2048
