package io.github.lens0021.teogeul.korean

import io.github.lens0021.teogeul.layout.LayoutData

private const val LANG_EN_CODE = 0
private const val LANG_KO_CODE = 3

enum class EngineMode(
    val properties: Properties,
    val layout: Array<IntArray>?,
    val jamoset: Array<Array<IntArray>>?,
    val combination: Array<IntArray>?,
    val addStroke: Array<IntArray>?,
    vararg prefValues: String,
) {
    DIRECT(Properties(), null, null, null, null),

    SEBUL_390(
        Properties(),
        LayoutData.JAMO_SEBUL_390,
        null,
        LayoutData.COMB_SEBULSIK,
        null,
        "keyboard_sebul_390",
    ),
    SEBUL_391(
        Properties(),
        LayoutData.JAMO_SEBUL_391,
        null,
        LayoutData.COMB_SEBULSIK,
        null,
        "keyboard_sebul_391",
    ),
    SEBUL_DANMOEUM(
        Properties(),
        LayoutData.JAMO_SEBUL_DANMOEUM,
        null,
        LayoutData.COMB_SEBUL_DANMOEUM,
        null,
        "keyboard_sebul_danmoeum",
    ),
    DUBULSIK(
        Properties(),
        LayoutData.JAMO_DUBUL_STANDARD,
        null,
        LayoutData.COMB_DUBUL_STANDARD,
        null,
        "keyboard_dubul_standard",
    ),
    DUBULSIK_NK(
        Properties(),
        LayoutData.JAMO_DUBUL_NK,
        null,
        LayoutData.COMB_DUBUL_STANDARD,
        null,
        "keyboard_dubul_nk",
    ),

    SEBUL_SUN_2014(
        Properties(),
        LayoutData.JAMO_SEBUL_SUN_2014,
        null,
        LayoutData.COMB_SEBUL_SUN_2014,
        null,
        "keyboard_sebul_sun_2014",
    ),
    SEBUL_3_2015M(
        Properties(),
        null,
        LayoutData.JAMOSET_SEBUL_3_2015M,
        LayoutData.COMB_SEBUL_3_2015,
        null,
        "keyboard_sebul_3_2015m",
    ),
    SEBUL_3_2015(
        Properties(),
        null,
        LayoutData.JAMOSET_SEBUL_3_2015,
        LayoutData.COMB_SEBUL_3_2015,
        null,
        "keyboard_sebul_3_2015",
    ),
    SEBUL_3_P3(
        Properties(),
        null,
        LayoutData.JAMOSET_SEBUL_3_P3,
        LayoutData.COMB_SEBUL_3_P3,
        null,
        "keyboard_sebul_3_p3",
    ),
    SEBUL_SHIN_ORIGINAL(
        Properties(),
        null,
        LayoutData.JAMOSET_SHIN_ORIGINAL,
        LayoutData.COMB_SEBUL_SHIN_ORIGINAL,
        null,
        "keyboard_sebul_shin_original",
    ),
    SEBUL_SHIN_EDIT(
        Properties(),
        null,
        LayoutData.JAMOSET_SHIN_EDIT,
        LayoutData.COMB_SEBUL_SHIN_ORIGINAL,
        null,
        "keyboard_sebul_shin_edit",
    ),
    SEBUL_SHIN_M(
        Properties(),
        null,
        LayoutData.JAMOSET_SHIN_M,
        LayoutData.COMB_SEBUL_SHIN_ORIGINAL,
        null,
        "keyboard_sebul_shin_m",
    ),

    SEBUL_AHNMATAE(
        Properties(true, false, false, false),
        LayoutData.JAMO_SEBUL_AHNMATAE,
        null,
        LayoutData.COMB_SEBUL_AHNMATAE,
        null,
        "keyboard_sebul_ahnmatae",
    ),

    NEBUL_1969(Properties(), LayoutData.JAMO_NEBUL_1969, null, LayoutData.COMB_NEBUL_1969, null, "keyboard_nebul_1969"),

    ENGLISH_QWERTY(
        Properties(LANG_EN_CODE, false, false, false, false, false, false),
        null,
        null,
        null,
        null,
        "keyboard_alphabet_qwerty",
    ),
    ENGLISH_DVORAK(
        Properties(LANG_EN_CODE, false, false, false, false, false, false),
        LayoutData.CONVERT_ENGLISH_DVORAK,
        null,
        null,
        null,
        "keyboard_alphabet_dvorak",
    ),
    ENGLISH_COLEMAK(
        Properties(LANG_EN_CODE, false, false, false, false, false, false),
        LayoutData.CONVERT_ENGLISH_COLEMAK,
        null,
        null,
        null,
        "keyboard_alphabet_colemak",
    ),
    ;

    val prefValues: Array<out String> = prefValues

    /**
     * geulbus-core(Rust) 엔진으로 조합하는 모드. 단일 자모 테이블 자판이 대상이다.
     * 상태 의존 자판(jamoSet 계열)과 모아치기(안마태), 특수 플래그를 쓰는
     * 네벌식은 아직 기존 HangulEngine 을 쓴다 (docs/geulbus-migration.md).
     */
    val useGeulbus: Boolean
        get() =
            when (this) {
                SEBUL_390, SEBUL_391, SEBUL_DANMOEUM, SEBUL_SUN_2014,
                DUBULSIK, DUBULSIK_NK,
                -> true
                else -> false
            }

    companion object {
        const val LANG_EN = LANG_EN_CODE
        const val LANG_KO = LANG_KO_CODE

        @JvmStatic
        fun get(defaultLayout: String): EngineMode {
            for (mode in values()) {
                if (mode.prefValues.isEmpty()) {
                    continue
                }
                for (prefValue in mode.prefValues) {
                    if (prefValue == defaultLayout) {
                        return mode
                    }
                }
            }
            return DIRECT
        }
    }

    class Properties {
        val languageCode: Int
        val altMode: Boolean
        val direct: Boolean
        val predictive: Boolean
        val fullMoachigi: Boolean
        val twelveEngine: Boolean
        val timeout: Boolean

        constructor(
            languageCode: Int,
            altMode: Boolean,
            direct: Boolean,
            predictive: Boolean,
            fullMoachigi: Boolean,
            twelveEngine: Boolean,
            timeout: Boolean,
        ) {
            this.languageCode = languageCode
            this.altMode = altMode
            this.direct = direct
            this.predictive = predictive
            this.fullMoachigi = fullMoachigi
            this.twelveEngine = twelveEngine
            this.timeout = timeout
        }

        constructor(
            altMode: Boolean,
            direct: Boolean,
            predictive: Boolean,
            fullMoachigi: Boolean,
            twelveEngine: Boolean,
            timeout: Boolean,
        ) : this(LANG_KO_CODE, altMode, direct, predictive, fullMoachigi, twelveEngine, timeout)

        constructor(
            direct: Boolean,
            predictive: Boolean,
            fullMoachigi: Boolean,
            twelveEngine: Boolean,
            timeout: Boolean,
        ) : this(false, direct, predictive, fullMoachigi, twelveEngine, timeout)

        constructor(predictive: Boolean, fullMoachigi: Boolean, twelveEngine: Boolean, timeout: Boolean) :
            this(false, fullMoachigi, predictive, twelveEngine, timeout)

        constructor(twelveEngine: Boolean, predictive: Boolean, timeout: Boolean) :
            this(false, predictive, false, twelveEngine, timeout)

        constructor() : this(false, false, false, false, false)
    }
}
