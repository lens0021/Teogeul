//! 실제 `LayoutData.kt` 테이블(부분 전사)로 변환기와 조합 동작을 검증한다.
//! 시나리오는 옛 `HangulEngine`/`KeyEventHandlerTest` 의 동작을 오라클로 삼는다.

use crate::{ComboRow, GeulbusEngine, JamoRow};
use std::sync::Arc;

fn row(key: char, normal: u32, shift: u32) -> JamoRow {
    JamoRow {
        key: key as u32,
        normal,
        shift,
    }
}

fn combo(a: u32, b: u32, to: u32) -> ComboRow {
    ComboRow { a, b, to }
}

/// 대문자는 시프트+소문자로 눌러 (확정 누적, 마지막 preedit)을 돌려준다.
fn typ(e: &Arc<GeulbusEngine>, keys: &str) -> (String, String) {
    let mut committed = String::new();
    let mut preedit = String::new();
    for ch in keys.chars() {
        let (key, shift) = if ch.is_ascii_uppercase() {
            (ch.to_ascii_lowercase() as u32, true)
        } else {
            (ch as u32, false)
        };
        let out = e.press(key, shift);
        committed.push_str(&out.commit);
        preedit = out.preedit;
    }
    (committed, preedit)
}

// ── 두벌식 표준 (JAMO_DUBUL_STANDARD 부분 전사, 호환 자모) ──────────────────

fn dubul() -> Arc<GeulbusEngine> {
    let rows = vec![
        row('1', '1' as u32, '!' as u32),
        row('r', 0x3131, 0x3132), // ㄱ ㄲ
        row('s', 0x3134, 0x3134), // ㄴ
        row('e', 0x3137, 0x3138), // ㄷ ㄸ
        row('q', 0x3142, 0x3143), // ㅂ ㅃ
        row('t', 0x3145, 0x3146), // ㅅ ㅆ
        row('d', 0x3147, 0x3147), // ㅇ
        row('w', 0x3148, 0x3149), // ㅈ ㅉ
        row('g', 0x314E, 0x314E), // ㅎ
        row('k', 0x314F, 0x314F), // ㅏ
        row('o', 0x3150, 0x3152), // ㅐ ㅒ
        row('j', 0x3153, 0x3153), // ㅓ
        row('h', 0x3157, 0x3157), // ㅗ
        row('l', 0x3163, 0x3163), // ㅣ
    ];
    let combos = vec![
        combo(0x1169, 0x1161, 0x116A), // ㅗ+ㅏ→ㅘ
        combo(0x11AB, 0x11BD, 0x11AC), // ㄴ+ㅈ→ㄵ
        combo(0x11AB, 0x11C2, 0x11AD), // ㄴ+ㅎ→ㄶ
    ];
    GeulbusEngine::new_plain("dubul-standard".into(), rows, combos).unwrap()
}

#[test]
fn dubul_syllable_with_batchim() {
    let e = dubul();
    let (c, p) = typ(&e, "rkr"); // ㄱㅏㄱ
    assert_eq!(c, "");
    assert_eq!(p, "각");
}

#[test]
fn dubul_dokkaebibul() {
    // 간 + ㅏ → "가" 확정, 나 조합.
    let e = dubul();
    typ(&e, "rks");
    assert_eq!(e.preedit(), "간");
    let out = e.press('k' as u32, false);
    assert_eq!(out.commit, "가");
    assert_eq!(out.preedit, "나");
}

#[test]
fn dubul_dokkaebibul_double_jong() {
    // 앉 + ㅏ → "안" 확정, 자 조합 (겹받침은 마지막 한 타만 이동).
    let e = dubul();
    let (c, p) = typ(&e, "dkswk");
    assert_eq!(c, "안");
    assert_eq!(p, "자");
}

#[test]
fn dubul_tense_consonant_via_shift() {
    // 시프트+ㄱ = ㄲ → 까.
    let e = dubul();
    let (c, p) = typ(&e, "Rk");
    assert_eq!(c, "");
    assert_eq!(p, "까");
}

#[test]
fn dubul_tense_never_batchim() {
    // ㄸ 은 받침이 될 수 없으므로 새 음절 초성: 가 + ㄸ → "가" 확정, ㄸ 조합.
    let e = dubul();
    typ(&e, "rk");
    let out = e.press('e' as u32, true); // ㄸ
    assert_eq!(out.commit, "가");
    assert_eq!(out.preedit, "ㄸ");
}

#[test]
fn dubul_compound_vowel() {
    // ㄱㅗㅏ → 과.
    let e = dubul();
    let (_c, p) = typ(&e, "rhk");
    assert_eq!(p, "과");
}

#[test]
fn dubul_backspace_unwinds_by_jamo() {
    // 각 → Bksp → 가 → Bksp → ㄱ → Bksp → 빈 상태(소비 안 함 = 앱이 지움).
    let e = dubul();
    typ(&e, "rkr");
    assert_eq!(e.backspace().preedit, "가");
    assert_eq!(e.backspace().preedit, "ㄱ");
    let out = e.backspace();
    assert_eq!(out.preedit, "");
    let out = e.backspace();
    assert!(!out.consumed);
}

#[test]
fn dubul_digit_and_symbol_literals() {
    // 숫자/기호는 조합을 확정하고 그대로 입력.
    let e = dubul();
    typ(&e, "rk"); // 가
    let out = e.press('1' as u32, false);
    assert_eq!(out.commit, "가1");
    assert!(out.consumed);
    let out = e.press('1' as u32, true); // !
    assert_eq!(out.commit, "!");
}

#[test]
fn dubul_unmapped_key_passes_through_when_idle() {
    let e = dubul();
    let out = e.press('.' as u32, false);
    assert!(!out.consumed);
    assert_eq!(out.commit, "");
}

// ── 세벌식 390 (JAMO_SEBUL_390 부분 전사, 첫가끝) ───────────────────────────

fn sebul390() -> Arc<GeulbusEngine> {
    let rows = vec![
        row('k', 0x1100, '5' as u32),  // 초성 ㄱ
        row('m', 0x1112, '1' as u32),  // 초성 ㅎ
        row('f', 0x1161, 0x11A9),      // 중성 ㅏ / 종성 ㄲ
        row('v', 0x1169, 0x11B6),      // 중성 ㅗ / 종성 ㅀ
        row('d', 0x1175, 0x11B0),      // 중성 ㅣ / 종성 ㄺ
        row('s', 0x11AB, 0x11AD),      // 종성 ㄴ / ㄶ
        row('w', 0x11AF, 0x11C0),      // 종성 ㄹ / ㅌ
        row('1', 0x11C2, 0x11BD),      // 종성 ㅎ / ㅈ
        row('2', 0x11BB, '@' as u32),  // 종성 ㅆ
    ];
    let combos = vec![
        combo(0x1169, 0x1161, 0x116A), // ㅗ+ㅏ→ㅘ
        combo(0x11AF, 0x11C2, 0x11B6), // ㄹ+ㅎ→ㅀ
    ];
    GeulbusEngine::new_plain("sebul-390".into(), rows, combos).unwrap()
}

#[test]
fn sebul_full_syllable() {
    let e = sebul390();
    let (c, p) = typ(&e, "mfs"); // ㅎㅏㄴ
    assert_eq!(c, "");
    assert_eq!(p, "한");
}

#[test]
fn sebul_compound_vowel() {
    // ㄱ ㅗ ㅏ → 과 (세벌식은 중성 키가 따로 있음).
    let e = sebul390();
    let (_c, p) = typ(&e, "kvf");
    assert_eq!(p, "과");
}

#[test]
fn sebul_compound_jong_by_keys() {
    // 갈 + 종성 ㅎ → 갏 (조합 테이블).
    let e = sebul390();
    let (_c, p) = typ(&e, "kfw1");
    assert_eq!(p, "갏");
}

#[test]
fn sebul_shift_jong() {
    // 가 + 시프트 f = 종성 ㄲ → 갂.
    let e = sebul390();
    let (_c, p) = typ(&e, "kfF");
    assert_eq!(p, "갂");
}

#[test]
fn sebul_no_dokkaebibul() {
    // 세벌식: 간 + 중성 ㅏ → "간" 확정 + 홀소리 ㅏ (도깨비불 없음).
    let e = sebul390();
    typ(&e, "kfs"); // 간
    let out = e.press('f' as u32, false);
    assert_eq!(out.commit, "간");
    assert_eq!(out.preedit, "ㅏ");
}

#[test]
fn sebul_digit_shift_row() {
    // 390 의 '1' 은 종성 ㅎ, 시프트는 종성 ㅈ. 빈 상태에서 종성은 홑낱자.
    let e = sebul390();
    let out = e.press('1' as u32, false);
    assert_eq!(out.preedit, "ㅎ");
}
