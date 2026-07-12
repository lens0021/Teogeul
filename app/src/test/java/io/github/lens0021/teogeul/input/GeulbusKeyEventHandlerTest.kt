package io.github.lens0021.teogeul.input

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import io.github.lens0021.teogeul.engine.GeulbusEngine
import io.github.lens0021.teogeul.korean.EngineMode
import io.github.lens0021.teogeul.korean.GeulbusHangul
import io.github.lens0021.teogeul.korean.HangulEngine
import io.github.lens0021.teogeul.model.KeyMappings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * geulbus-core(Rust) 엔진 경로의 종단 검증: LayoutData 테이블 → FFI → 조합 →
 * InputConnection 반영. 시나리오는 옛 HangulEngine 의 동작을 오라클로 삼는다.
 */
@RunWith(RobolectricTestRunner::class)
class GeulbusKeyEventHandlerTest {
    private class RecordingInputConnection :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var committed: String = ""
        var composing: String = ""

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            committed += text?.toString() ?: ""
            composing = ""
            return true
        }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int,
        ): Boolean {
            composing = text?.toString() ?: ""
            return true
        }

        override fun finishComposingText(): Boolean {
            committed += composing
            composing = ""
            return true
        }
    }

    private fun buildHandler(
        inputConnection: RecordingInputConnection,
        engine: GeulbusEngine,
    ): KeyEventHandler =
        KeyEventHandler(
            layoutConverter = LayoutConverter(),
            inputConnectionProvider = { inputConnection },
            hangulEngineProvider = { HangulEngine() },
            geulbusEngineProvider = { engine },
            directInputModeProvider = { false },
            alphabetLayoutProvider = { "keyboard_alphabet_qwerty" },
            hardLangKeyProvider = { null },
            keyMappingsProvider = { KeyMappings.EMPTY },
            currentLanguageProvider = { EngineMode.LANG_KO },
            toggleLanguage = {},
            resetCharComposition = { engine.reset() },
            currentInputEditorInfoProvider = { null },
            sendDefaultEditorAction = {},
            markInput = {},
            sendKeyEvent = {},
            openIMEPicker = {},
        )

    private fun keyEvent(
        keyCode: Int,
        shift: Boolean = false,
    ): KeyEvent =
        KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            if (shift) KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON else 0,
        )

    @Test
    fun dubulsik_composes_syllable_with_batchim() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.DUBULSIK)!!)

        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R)) // ㄱ
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_K)) // ㅏ
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R)) // ㄱ 받침

        assertEquals("", ic.committed)
        assertEquals("각", ic.composing)
    }

    @Test
    fun dubulsik_dokkaebibul() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.DUBULSIK)!!)

        // 간 + ㅏ → "가" 확정, 나 조합.
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_K))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_S))
        assertEquals("간", ic.composing)
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_K))

        assertEquals("가", ic.committed)
        assertEquals("나", ic.composing)
    }

    @Test
    fun dubulsik_tense_consonant_with_shift() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.DUBULSIK)!!)

        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R, shift = true)) // ㄲ
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_K)) // ㅏ

        assertEquals("까", ic.composing)
    }

    @Test
    fun dubulsik_backspace_unwinds_jamo() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.DUBULSIK)!!)

        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_K))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_R))
        assertEquals("각", ic.composing)

        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_DEL))
        assertEquals("가", ic.composing)
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_DEL))
        assertEquals("ㄱ", ic.composing)
    }

    @Test
    fun sebul390_composes_syllable() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.SEBUL_390)!!)

        // ㅎ(m) ㅏ(f) ㄴ(s) → 한.
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_M))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_F))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_S))

        assertEquals("한", ic.composing)
    }

    @Test
    fun sebul390_unmapped_symbol_passes_through() {
        val ic = RecordingInputConnection()
        val handler = buildHandler(ic, GeulbusHangul.create(EngineMode.SEBUL_390)!!)

        // 배열에 있는 리터럴이 아닌 일반 문자 입력도 그대로 들어가야 한다.
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_M))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_F))
        handler.processKeyEvent(keyEvent(KeyEvent.KEYCODE_COMMA))

        assertEquals("하,", ic.committed)
    }
}
