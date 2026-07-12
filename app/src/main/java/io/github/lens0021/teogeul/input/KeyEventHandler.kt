package io.github.lens0021.teogeul.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import io.github.lens0021.teogeul.engine.GeulbusEngine
import io.github.lens0021.teogeul.engine.KeyOutcome
import io.github.lens0021.teogeul.korean.EngineMode
import io.github.lens0021.teogeul.korean.HangulEngine
import io.github.lens0021.teogeul.model.KeyMappings
import io.github.lens0021.teogeul.model.KeyStroke
import io.github.lens0021.teogeul.model.VirtualKeyAction

class KeyEventHandler(
    private val layoutConverter: LayoutConverter,
    private val inputConnectionProvider: () -> InputConnection?,
    private val hangulEngineProvider: () -> HangulEngine,
    private val geulbusEngineProvider: () -> GeulbusEngine? = { null },
    private val directInputModeProvider: () -> Boolean,
    private val alphabetLayoutProvider: () -> String,
    private val hardLangKeyProvider: () -> KeyStroke?, // deprecated, kept for compatibility
    private val keyMappingsProvider: () -> KeyMappings,
    private val currentLanguageProvider: () -> Int,
    private val toggleLanguage: () -> Unit,
    private val resetCharComposition: () -> Unit,
    private val currentInputEditorInfoProvider: () -> EditorInfo?,
    private val sendDefaultEditorAction: (Boolean) -> Unit,
    private val markInput: () -> Unit,
    private val sendKeyEvent: (KeyEvent) -> Unit,
    private val openIMEPicker: () -> Unit,
) {
    companion object {
        val SHIFT_CONVERT =
            arrayOf(
                intArrayOf('`'.code, '~'.code),
                intArrayOf('1'.code, '!'.code),
                intArrayOf('2'.code, '@'.code),
                intArrayOf('3'.code, '#'.code),
                intArrayOf('4'.code, '$'.code),
                intArrayOf('5'.code, '%'.code),
                intArrayOf('6'.code, '^'.code),
                intArrayOf('7'.code, '&'.code),
                intArrayOf('8'.code, '*'.code),
                intArrayOf('9'.code, '('.code),
                intArrayOf('0'.code, ')'.code),
                intArrayOf('-'.code, '_'.code),
                intArrayOf('='.code, '+'.code),
                intArrayOf('['.code, '{'.code),
                intArrayOf(']'.code, '}'.code),
                intArrayOf('\\'.code, '|'.code),
                intArrayOf(';'.code, ':'.code),
                intArrayOf('\''.code, '"'.code),
                intArrayOf(','.code, '<'.code),
                intArrayOf('.'.code, '>'.code),
                intArrayOf('/'.code, '?'.code),
            )

        private fun isModifierKey(keyCode: Int): Boolean =
            when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                -> true
                else -> false
            }
    }

    fun processKeyEvent(ev: KeyEvent): Boolean {
        val inputConnection = inputConnectionProvider() ?: return false
        val hangulEngine = hangulEngineProvider()
        val key = ev.keyCode

        // Modifier keys should not break composition
        if (isModifierKey(key)) {
            return false
        }

        // Ctrl key handling (available since API 11, always true for minSdk 26)
        if (ev.isCtrlPressed || ev.isAltPressed || ev.isMetaPressed) {
            val converted =
                layoutConverter.convertKeyEventForShortcut(ev, alphabetLayoutProvider())
            inputConnection.sendKeyEvent(converted ?: ev)
            return true
        }

        if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN) {
            resetCharComposition()
            return false
        }

        // Language switch key is not handled by IME - let system handle it
        if (key == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            resetCharComposition()
            return false
        }

        // Handle custom key mappings
        val action = keyMappingsProvider().mappings.firstOrNull { it.physicalKey == key }?.virtualAction
        if (action != null) {
            resetCharComposition()
            when (action) {
                is VirtualKeyAction.ToggleLanguage -> {
                    toggleLanguage()
                }
                is VirtualKeyAction.SendKeyEvent -> {
                    // Create a new KeyEvent with the mapped key code
                    val newEvent =
                        KeyEvent(
                            ev.downTime,
                            ev.eventTime,
                            ev.action,
                            action.keyCode,
                            ev.repeatCount,
                            0, // metaState = 0 (no modifiers)
                            ev.deviceId,
                            ev.scanCode,
                        )
                    sendKeyEvent(newEvent)
                }
                is VirtualKeyAction.OpenIMEPicker -> {
                    openIMEPicker()
                }
            }
            return true
        }

        // Handle custom language key combination (deprecated, for backward compatibility)
        val hardLangKey = hardLangKeyProvider()
        if (hardLangKey != null && key == hardLangKey.keyCode) {
            resetCharComposition()
            toggleLanguage()
            return true
        }

        if (ev.isPrintingKey) {
            val qwertyCharCode = layoutConverter.getQwertyCharCode(ev)
            // Don't apply Alt meta state for character input - Alt is only used for shortcuts/language switching
            val code =
                if (qwertyCharCode != null) {
                    qwertyCharCode
                } else {
                    ev.getUnicodeChar(ev.metaState)
                }
            inputChar(code.toChar(), ev.isShiftPressed)
            markInput()
            return true
        } else if (key == KeyEvent.KEYCODE_SPACE) {
            resetCharComposition()
            inputConnection.commitText(" ", 1)
            return true
        } else if (key == KeyEvent.KEYCODE_DEL) {
            val geulbus = geulbusEngineProvider()
            if (geulbus != null) {
                val out = geulbus.backspace()
                if (!out.consumed) {
                    inputConnection.deleteSurroundingText(1, 0)
                } else {
                    if (out.deleteBefore > 0u) {
                        inputConnection.deleteSurroundingText(out.deleteBefore.toInt(), 0)
                    }
                    inputConnection.setComposingText(out.preedit, 1)
                }
                return true
            }
            if (!hangulEngine.backspace()) {
                resetCharComposition()
                inputConnection.deleteSurroundingText(1, 0)
            }
            if (hangulEngine.composing == "") {
                resetCharComposition()
            }
            return true
        } else if (key == KeyEvent.KEYCODE_ENTER) {
            resetCharComposition()
            val editorInfo = currentInputEditorInfoProvider()
            return when (editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
                EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO -> {
                    sendDefaultEditorAction(true)
                    true
                }

                else -> false
            }
        } else {
            resetCharComposition()
        }

        return false
    }

    private fun inputChar(
        code: Char,
        isShiftPressed: Boolean,
    ) {
        var shift = if (isShiftPressed) 1 else 0
        var mutableCode = code
        var isDirect = false

        if (mutableCode.code == 128) {
            mutableCode = if (shift > 0) ','.code.toChar() else '.'.code.toChar()
            shift = 0
            isDirect = true
        }

        val originalCode = mutableCode
        for (item in SHIFT_CONVERT) {
            if (mutableCode.code == item[1]) {
                mutableCode = item[0].toChar()
                shift = 1
            }
        }

        val inputConnection = inputConnectionProvider()
        if (directInputModeProvider()) {
            mutableCode = originalCode
            resetCharComposition()
            directInput(mutableCode, shift > 0)
            return
        } else if (isDirect) {
            resetCharComposition()
            inputConnection?.commitText(String(charArrayOf(originalCode)), 1)
            resetCharComposition()
            return
        }

        val geulbus = geulbusEngineProvider()
        if (geulbus != null) {
            val out = geulbus.press(mutableCode.lowercaseChar().code.toUInt(), shift > 0)
            applyOutcome(out, originalCode, shift > 0)
            return
        }

        val hangulEngine = hangulEngineProvider()
        val inputCode = hangulEngine.inputCode(mutableCode.lowercaseChar().code, shift)
        if (inputCode != -1) {
            if (hangulEngine.inputJamo(inputCode) == 0) {
                inputConnection?.commitText(String(charArrayOf(inputCode.toChar())), 1)
                resetCharComposition()
            }
        } else {
            resetCharComposition()
            if (shift > 0) {
                mutableCode = originalCode.uppercaseChar()
                for (item in SHIFT_CONVERT) {
                    if (mutableCode.code == item[0]) {
                        mutableCode = item[1].toChar()
                    }
                }
            }
            inputConnection?.commitText(String(charArrayOf(mutableCode)), 1)
            resetCharComposition()
        }
    }

    /** geulbus 엔진의 처리 결과를 InputConnection 에 반영한다. */
    private fun applyOutcome(
        out: KeyOutcome,
        originalCode: Char,
        shift: Boolean,
    ) {
        val inputConnection = inputConnectionProvider() ?: return
        if (!out.consumed) {
            // 배열에 없는 글쇠: 원래 문자를 그대로 입력한다.
            var ch = originalCode
            if (shift) {
                ch = ch.uppercaseChar()
                for (item in SHIFT_CONVERT) {
                    if (ch.code == item[0]) {
                        ch = item[1].toChar()
                    }
                }
            }
            inputConnection.commitText(ch.toString(), 1)
            return
        }
        if (out.commit.isNotEmpty()) {
            inputConnection.commitText(out.commit, 1)
        }
        inputConnection.setComposingText(out.preedit, 1)
    }

    private fun directInput(
        code: Char,
        shift: Boolean,
    ) {
        var mutableCode = code
        if (shift) {
            mutableCode = mutableCode.uppercaseChar()
            for (item in SHIFT_CONVERT) {
                if (mutableCode.code == item[0]) {
                    mutableCode = item[1].toChar()
                    break
                }
            }
        }
        inputConnectionProvider()?.commitText(mutableCode.toString(), 1)
    }
}
