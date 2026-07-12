package io.github.lens0021.teogeul.korean

import io.github.lens0021.teogeul.engine.ComboRow
import io.github.lens0021.teogeul.engine.GeulbusEngine
import io.github.lens0021.teogeul.engine.JamoRow

/**
 * geulbus-core(Rust) 조합 엔진 팩토리.
 *
 * LayoutData 의 자판/조합 테이블을 그대로 넘기면 Rust 쪽 변환기가 날개셋
 * Layout 으로 컴파일한다. 자세한 이행 설계는 docs/geulbus-migration.md 참고.
 */
object GeulbusHangul {
    fun create(mode: EngineMode): GeulbusEngine? {
        val layout = mode.layout ?: return null
        val rows =
            layout.map {
                JamoRow(
                    key = it[0].toUInt(),
                    normal = it[1].toUInt(),
                    shift = it[2].toUInt(),
                )
            }
        val combos =
            (mode.combination ?: emptyArray()).map {
                ComboRow(
                    a = it[0].toUInt(),
                    b = it[1].toUInt(),
                    to = it[2].toUInt(),
                )
            }
        return GeulbusEngine.newPlain(mode.name, rows, combos)
    }
}
