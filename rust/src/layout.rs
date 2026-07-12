//! teogeul 자판 테이블(`LayoutData.kt`)을 geulbus-core `Layout` 으로 변환한다.
//!
//! teogeul 행은 `[key, normal, shift]` (key = 비시프트 ASCII). 값은 첫가끝 자모
//! (0x1100..), 호환 자모(두벌식, 0x31xx), 또는 ASCII 문자 리터럴이다.
//!
//! 단일 테이블 자판만 다룬다. 상태 의존 자판(jamoSet: 신세벌식·3-2015 계열)은
//! preserveState 플래그(0x10000)가 반모음 겹모음 진행(과=ㄱ+p+f)과 갈마들이
//! 종성(곶=ㄱ+v+f)을 구분하는데, 이는 날개셋의 가상 낱자 + 오토마타 상태로만
//! 충실히 표현할 수 있어 geulbus-core 의 후속 확장을 기다린다.
//! 설계 근거는 `docs/geulbus-migration.md` 참고.

use geulbus_core::config::{BkspBehavior, Layout};
use geulbus_core::expr::Expr;
use geulbus_core::unit::{self, Category};
use std::collections::HashMap;

use crate::{ComboRow, JamoRow};

/// 옛 엔진의 preserveState 플래그 등 상위 비트를 제거한 코드포인트.
fn plain(v: u32) -> u32 {
    v & 0xFFFF
}

/// teogeul 테이블 값 하나의 해석 결과.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Val {
    /// 매핑 없음(0). 그 키의 원래 문자를 그대로 확정한다.
    None,
    /// 한글 낱자.
    Unit(Category, u32),
    /// 문자 리터럴(기호/숫자 등). 조합 확정 후 이 문자를 입력.
    Literal(u32),
}

/// 값 하나를 해석한다. 호환 자모 자음은 두벌식 이중 배치를 위해 초성으로 두고
/// (배치는 `Layout.dubeol` 이 담당), 겹받침 전용 호환 자모(ㄳ 등)는 종성으로 둔다.
fn classify(v: u32) -> Val {
    let cp = plain(v);
    if cp == 0 {
        return Val::None;
    }
    if let Some(cat) = unit::category_of_codepoint(cp) {
        return Val::Unit(cat, cp);
    }
    match cp {
        0x3131..=0x314E => {
            if let Some(c) = hanmo::cho_cp_for_compat(cp) {
                Val::Unit(Category::Cho, c)
            } else if let Some(j) = hanmo::jong_cp_for_compat(cp) {
                Val::Unit(Category::Jong, j)
            } else {
                Val::Literal(cp)
            }
        }
        0x314F..=0x3163 => match hanmo::jung_cp_for_compat(cp) {
            Some(j) => Val::Unit(Category::Jung, j),
            None => Val::Literal(cp),
        },
        _ => Val::Literal(cp),
    }
}

/// 값-식 문자열 조각으로 렌더링. `fallback` 은 매핑 없는 키의 원래 문자.
fn render(v: Val, fallback: u32) -> String {
    match v {
        Val::None => format!("0x{fallback:X}"),
        Val::Unit(_, cp) => format!("H3|0x{cp:X}"),
        Val::Literal(ch) => format!("0x{ch:X}"),
    }
}

/// 시프트 시 만들어지는 ASCII (문자는 대문자, 기호/숫자는 미국 QWERTY 짝).
pub(crate) fn shifted(key: u32) -> Option<u32> {
    let ch = char::from_u32(key)?;
    if ch.is_ascii_lowercase() {
        return Some(ch.to_ascii_uppercase() as u32);
    }
    let pair = match ch {
        '`' => '~',
        '1' => '!',
        '2' => '@',
        '3' => '#',
        '4' => '$',
        '5' => '%',
        '6' => '^',
        '7' => '&',
        '8' => '*',
        '9' => '(',
        '0' => ')',
        '-' => '_',
        '=' => '+',
        '[' => '{',
        ']' => '}',
        '\\' => '|',
        ';' => ':',
        '\'' => '"',
        ',' => '<',
        '.' => '>',
        '/' => '?',
        _ => return None,
    };
    Some(pair as u32)
}

fn empty_layout(name: &str) -> Layout {
    Layout {
        name: name.to_string(),
        keys: HashMap::new(),
        combine: HashMap::new(),
        virtual_units: HashMap::new(),
        final_conv: HashMap::new(),
        shortcuts: Vec::new(),
        automata: HashMap::new(),
        automata_start: 0,
        bksp: BkspBehavior::default(),
        dubeol: false,
    }
}

/// 조합 테이블 행들을 `Layout.combine` 에 넣는다. preserveState 플래그가 붙은
/// 중복 행은 플래그 제거로 자연히 합쳐진다.
fn add_combos(layout: &mut Layout, combos: &[ComboRow]) {
    for c in combos {
        let (a, b, to) = (plain(c.a), plain(c.b), plain(c.to));
        if let Some(cat) = unit::category_of_codepoint(a) {
            layout.combine.insert((cat, a, b), to);
        }
    }
}

/// 한 키의 값(비시프트/시프트)을 keys 맵에 등록한다.
fn add_key(
    keys: &mut HashMap<u32, Expr>,
    dubeol: &mut bool,
    key: u32,
    val: u32,
    expr_of: impl Fn(u32, u32) -> Result<Expr, String>,
) -> Result<(), String> {
    // 호환 자모 자음이 하나라도 있으면 두벌식 배치(받침 이중 역할 + 도깨비불).
    if matches!(plain(val), 0x3131..=0x314E) {
        *dubeol = true;
    }
    keys.insert(key, expr_of(key, val)?);
    Ok(())
}

/// 단일 테이블 자판(두벌식, 390, 안마태 등) → Layout.
pub fn build_plain(name: &str, rows: &[JamoRow], combos: &[ComboRow]) -> Result<Layout, String> {
    let mut layout = empty_layout(name);
    for row in rows {
        // key 128(온점/반점 특수키)은 프런트엔드가 엔진 앞에서 처리한다.
        if row.key >= 0x7F {
            continue;
        }
        let expr_of = |key: u32, v: u32| {
            Expr::parse(&render(classify(v), key)).map_err(|e| format!("값-식 생성 실패: {e}"))
        };
        add_key(&mut layout.keys, &mut layout.dubeol, row.key, row.normal, expr_of)?;
        if let Some(sk) = shifted(row.key) {
            add_key(&mut layout.keys, &mut layout.dubeol, sk, row.shift, expr_of)?;
        }
    }
    add_combos(&mut layout, combos);
    Ok(layout)
}

