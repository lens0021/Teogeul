package io.github.lens0021.teogeul

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.lens0021.teogeul.config.SettingsDefaults
import io.github.lens0021.teogeul.config.SettingsRepository
import io.github.lens0021.teogeul.config.SettingsSnapshot
import io.github.lens0021.teogeul.config.SettingsValues
import io.github.lens0021.teogeul.config.settingsDataStore
import io.github.lens0021.teogeul.input.CommitComposingTextEvent
import io.github.lens0021.teogeul.input.InputEventBus
import io.github.lens0021.teogeul.input.InputKeyEvent
import io.github.lens0021.teogeul.input.InputTimeoutEvent
import io.github.lens0021.teogeul.input.KeyEventHandler
import io.github.lens0021.teogeul.input.KeyUpEvent
import io.github.lens0021.teogeul.engine.GeulbusEngine
import io.github.lens0021.teogeul.input.LayoutConverter
import io.github.lens0021.teogeul.korean.EngineMode
import io.github.lens0021.teogeul.korean.GeulbusHangul
import io.github.lens0021.teogeul.korean.HangulEngine
import io.github.lens0021.teogeul.korean.HangulEngine.FinishComposingEvent
import io.github.lens0021.teogeul.korean.HangulEngine.HangulEngineEvent
import io.github.lens0021.teogeul.korean.HangulEngine.HangulEngineListener
import io.github.lens0021.teogeul.korean.HangulEngine.SetComposingEvent
import io.github.lens0021.teogeul.model.KeyMappings
import io.github.lens0021.teogeul.model.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class IMEService() :
    InputMethodService(),
    HangulEngineListener {
    companion object {
        private var instance: IMEService? = null

        @JvmStatic
        fun getInstance(): IMEService? = instance
    }

    protected var inputConnection: InputConnection? = null
    protected var directInputMode: Boolean = true

    private var consumeDownEvent: Boolean = false

    var hangulEngine: HangulEngine = HangulEngine()

    // geulbus-core(Rust) 엔진. useGeulbus 모드에서만 non-null 이며, 이때
    // hangulEngine 은 쓰이지 않는다 (docs/geulbus-migration.md).
    var geulbusEngine: GeulbusEngine? = null

    lateinit var currentEngineMode: EngineMode
    var currentLanguage: Int = EngineMode.LANG_KO

    var timeoutEnabled: Boolean = false

    var hardwareMoachigiEnabled: Boolean = false
    var fullMoachigiEnabled: Boolean = true
    var moachigiDelayMs: Int = 0

    var standardJamoEnabled: Boolean = false

    var alphabetLayout: String = "keyboard_alphabet_qwerty"

    var timeoutHandler: Handler? = null

    var hardwareLangKey: KeyStroke? = null // deprecated
    var keyMappings: KeyMappings = KeyMappings.EMPTY
    private var skipNextWindowHiddenSwitch: Boolean = false

    private val layoutConverter = LayoutConverter()
    private val keyEventHandler by lazy {
        KeyEventHandler(
            layoutConverter = layoutConverter,
            inputConnectionProvider = { inputConnection },
            hangulEngineProvider = { hangulEngine },
            geulbusEngineProvider = { geulbusEngine },
            directInputModeProvider = { directInputMode },
            alphabetLayoutProvider = { alphabetLayout },
            hardLangKeyProvider = { hardwareLangKey },
            keyMappingsProvider = { keyMappings },
            currentLanguageProvider = { currentLanguage },
            toggleLanguage = { toggleLanguage() },
            resetCharComposition = { resetCharComposition() },
            currentInputEditorInfoProvider = { currentInputEditorInfo },
            sendDefaultEditorAction = { sendDefaultEditorAction(it) },
            markInput = {},
            sendKeyEvent = { keyEvent ->
                currentInputConnection?.sendKeyEvent(keyEvent)
            },
            openIMEPicker = {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
        )
    }

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var eventJob: Job? = null
    private val settingsRepository by lazy { SettingsRepository(applicationContext.settingsDataStore) }

    init {
        instance = this

        hangulEngine.listener = this
    }

    constructor(context: Context) : this() {
        attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()
        eventJob =
            eventScope.launch {
                InputEventBus.events.collect { event ->
                    when (event) {
                        is InputKeyEvent -> handleInputKey(event)
                        is KeyUpEvent -> handleKeyUp(event)
                        is InputTimeoutEvent -> handleInputTimeout(event)
                        is CommitComposingTextEvent -> handleCommitComposingText(event)
                    }
                }
            }
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        resetCharComposition()
        super.onStartInputView(attribute, restarting)
        inputConnection = currentInputConnection
        setCandidatesViewShown(false)
        directInputMode = inputConnection == null
        applyPreferences(resetLanguage = true)
        handleNoHardwareOnStart()
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)
        inputConnection = currentInputConnection
        applyPreferences(resetLanguage = true)
    }

    override fun onFinishInput() {
        resetCharComposition()
        super.onFinishInput()
    }

    override fun onEvent(event: HangulEngineEvent) {
        when (event) {
            is FinishComposingEvent -> inputConnection?.finishComposingText()
            is SetComposingEvent -> inputConnection?.setComposingText(event.composing, 1)
        }
    }

    private fun handleKeyUp(
        @Suppress("UNUSED_PARAMETER") event: KeyUpEvent,
    ) {
        // Shift/Caps 키 상태는 시스템에서 처리합니다.
    }

    private fun handleInputTimeout(
        @Suppress("UNUSED_PARAMETER") event: InputTimeoutEvent,
    ) {
        if (timeoutEnabled) {
            resetCharComposition()
        }
    }

    private fun handleInputKey(event: InputKeyEvent) {
        val keyEvent = event.keyEvent
        // Shift/Caps 키 처리는 시스템에 위임합니다.
        val ret = keyEventHandler.processKeyEvent(keyEvent)
        event.isCancelled = ret
    }

    private fun handleCommitComposingText(
        @Suppress("UNUSED_PARAMETER") event: CommitComposingTextEvent,
    ) {
        currentInputConnection.finishComposingText()
    }

    private fun applyPreferences(resetLanguage: Boolean = false) {
        val snapshot = runBlocking { settingsRepository.snapshot() }
        hardwareMoachigiEnabled = snapshot.hardwareUseMoachigi
        fullMoachigiEnabled = snapshot.hardwareFullMoachigi
        moachigiDelayMs = snapshot.hardwareFullMoachigiDelay
        standardJamoEnabled = snapshot.systemUseStandardJamo
        if (resetLanguage) {
            applyStartLanguage(snapshot.systemStartHangulMode)
        }
        hardwareLangKey = KeyStroke.parse(snapshot.systemHardwareLangKeyStroke) // deprecated
        keyMappings = KeyMappings.parse(snapshot.systemKeyMappings)

        // 서브타입에서 레이아웃을 지정했다면 우선 적용하고, 없으면 앱 설정을 사용합니다.
        alphabetLayout = resolveAlphabetLayout(snapshot)

        val modeKey =
            if (currentLanguage == EngineMode.LANG_EN) {
                alphabetLayout
            } else {
                snapshot.hardwareHangulLayout
            }
        applyEngineMode(EngineMode.get(modeKey))
    }

    private fun applyStartLanguage(mode: String) {
        currentLanguage =
            when (mode) {
                SettingsValues.START_HANGUL_MODE_START_HANGUL -> EngineMode.LANG_KO
                SettingsValues.START_HANGUL_MODE_START_ENGLISH -> EngineMode.LANG_EN
                "new_input",
                "always",
                -> EngineMode.LANG_KO
                else -> currentLanguage
            }
    }

    private fun applyEngineMode(mode: EngineMode) {
        currentEngineMode = mode
        if (mode == EngineMode.DIRECT) {
            directInputMode = true
            timeoutEnabled = false
            fullMoachigiEnabled = false
            geulbusEngine = null
            hangulEngine.jamoTable = null
            hangulEngine.setCombinationTable(null)
            return
        }

        val prop = mode.properties
        directInputMode = prop.direct
        timeoutEnabled = prop.timeout
        fullMoachigiEnabled = prop.fullMoachigi

        if (mode.useGeulbus) {
            geulbusEngine = GeulbusHangul.create(mode)
            hangulEngine.jamoTable = null
            hangulEngine.setCombinationTable(null)
            return
        }

        geulbusEngine = null
        if (mode.jamoset != null) {
            hangulEngine.jamoSet = mode.jamoset
        } else {
            hangulEngine.jamoTable = mode.layout
        }
        hangulEngine.setCombinationTable(mode.combination)
        hangulEngine.firstMidEnd = standardJamoEnabled
        hangulEngine.moachigi = hardwareMoachigiEnabled
        hangulEngine.fullMoachigi = fullMoachigiEnabled
        if (fullMoachigiEnabled) {
            timeoutEnabled = true
        }
    }

    private fun resetCharComposition() {
        geulbusEngine?.let {
            it.reset()
            inputConnection?.finishComposingText()
        }
        hangulEngine.resetComposition()
    }

    private fun toggleLanguage() {
        currentLanguage =
            if (currentLanguage == EngineMode.LANG_EN) {
                EngineMode.LANG_KO
            } else {
                EngineMode.LANG_EN
            }
        applyPreferences()
    }

    override fun hideWindow() {
        super.hideWindow()
        directInputMode = true
        hideStatusIcon()
    }

    override fun onDestroy() {
        super.onDestroy()
        eventJob?.cancel()
        eventScope.cancel()
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (!consumeDownEvent) {
            return super.onKeyUp(keyCode, event)
        }
        InputEventBus.emitBlocking(KeyUpEvent(event))
        return true
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        startTimeoutIfNeeded()
        val inputKeyEvent = InputKeyEvent(event)
        InputEventBus.emitBlocking(inputKeyEvent)
        consumeDownEvent = inputKeyEvent.isCancelled
        if (!consumeDownEvent) {
            return super.onKeyDown(keyCode, event)
        }
        return consumeDownEvent
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = super.onEvaluateInputViewShown()

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (skipNextWindowHiddenSwitch) {
            skipNextWindowHiddenSwitch = false
            return
        }
        if (!isHardwareKeyboardActive()) {
            switchToPreviousInputMethod()
        }
    }

    override fun requestHideSelf(flag: Int) {
        super.requestHideSelf(flag)
        hideWindow()
    }

    override fun setCandidatesViewShown(shown: Boolean) {
        super.setCandidatesViewShown(shown)
        if (shown) {
            showWindow(true)
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentTopInsets = outInsets.visibleTopInsets
    }

    private fun resolveAlphabetLayout(snapshot: SettingsSnapshot): String {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        val currentSubtype = imm?.currentInputMethodSubtype
        val subtypeLayout =
            currentSubtype?.extraValue?.let { extraValue ->
                Regex("KeyboardLayoutSet=(\\w+)").find(extraValue)?.groupValues?.get(1)
            }
        return if (subtypeLayout != null &&
            subtypeLayout != SettingsDefaults.HARDWARE_ALPHABET_LAYOUT
        ) {
            subtypeLayout
        } else {
            snapshot.hardwareAlphabetLayout
        }
    }

    private fun handleNoHardwareOnStart() {
        if (isHardwareKeyboardActive()) {
            return
        }
        skipNextWindowHiddenSwitch = true
        switchToNextInputMethod(false)
    }

    private fun isHardwareKeyboardActive(): Boolean {
        val config = resources.configuration
        return config.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun startTimeoutIfNeeded() {
        if (timeoutHandler != null) {
            return
        }
        // 모아치기 입력 지연 타이머는 중복으로 등록하지 않습니다.
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutHandler?.postDelayed({
            InputEventBus.emitBlocking(InputTimeoutEvent())
            timeoutHandler = null
        }, moachigiDelayMs.toLong())
    }
}
