//! teogeul-engine — geulbus-core 를 감싸 Android(Kotlin/UniFFI)에 노출하는
//! 한글 조합 엔진. 자판 데이터는 Kotlin(`LayoutData.kt`)이 소유하고, 엔진 생성
//! 시 테이블을 넘겨받아 `layout` 모듈이 geulbus-core `Layout` 으로 변환한다.

use std::sync::{Arc, Mutex};

mod layout;

uniffi::setup_scaffolding!();

/// 자판 테이블 한 행: 비시프트 ASCII 키와 (비시프트, 시프트) 값.
/// 값은 첫가끝 자모, 호환 자모(두벌식), 또는 ASCII 문자 리터럴.
#[derive(uniffi::Record)]
pub struct JamoRow {
    pub key: u32,
    pub normal: u32,
    pub shift: u32,
}

/// 낱자 조합 규칙 한 행: a + b → to (같은 갈래의 첫가끝 코드포인트).
#[derive(uniffi::Record)]
pub struct ComboRow {
    pub a: u32,
    pub b: u32,
    pub to: u32,
}

/// 키 하나 처리 결과.
#[derive(uniffi::Record)]
pub struct KeyOutcome {
    /// 응용에 확정 입력할 문자열(없으면 빈 문자열).
    pub commit: String,
    /// 현재 조합 중 표시(preedit). 없으면 빈 문자열.
    pub preedit: String,
    /// 엔진이 이 키를 소비했는지. false 면 원래 키를 응용에 넘긴다.
    pub consumed: bool,
    /// 커서 앞의 이미 확정된 글자 N개를 지워달라는 요청(BkspAttach).
    pub delete_before: u32,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("자판 변환 실패: {message}")]
    BadLayout { message: String },
}

/// geulbus-core 기반 한글 조합 엔진.
#[derive(uniffi::Object)]
pub struct GeulbusEngine {
    inner: Mutex<geulbus_core::Engine>,
}

fn outcome(o: geulbus_core::KeyOutcome) -> KeyOutcome {
    KeyOutcome {
        commit: o.commit,
        preedit: o.preedit,
        consumed: o.consumed,
        delete_before: o.delete_before,
    }
}

#[uniffi::export]
impl GeulbusEngine {
    /// 단일 테이블 자판(두벌식, 세벌식 390/최종, 안마태 등).
    /// 호환 자모 자음이 있으면 두벌식 배치(받침 이중 역할 + 도깨비불)가 켜진다.
    #[uniffi::constructor]
    pub fn new_plain(
        name: String,
        rows: Vec<JamoRow>,
        combos: Vec<ComboRow>,
    ) -> Result<Arc<Self>, EngineError> {
        let layout = layout::build_plain(&name, &rows, &combos)
            .map_err(|message| EngineError::BadLayout { message })?;
        Ok(Arc::new(Self {
            inner: Mutex::new(geulbus_core::Engine::new(layout)),
        }))
    }

    /// 인쇄 글쇠 하나를 처리한다. `key` 는 비시프트 ASCII(QWERTY 기준),
    /// `shift` 가 참이면 시프트 짝(대문자/기호)으로 바꿔 배열을 찾는다.
    pub fn press(&self, key: u32, shift: bool) -> KeyOutcome {
        let ascii = if shift {
            layout::shifted(key).unwrap_or(key)
        } else {
            key
        };
        let Ok(ascii) = u8::try_from(ascii) else {
            return KeyOutcome {
                commit: String::new(),
                preedit: String::new(),
                consumed: false,
                delete_before: 0,
            };
        };
        outcome(self.inner.lock().unwrap().press(ascii, false))
    }

    /// 백스페이스: 낱자 단위로 되돌린다. `consumed == false` 면 지울 조합이
    /// 없다는 뜻이므로 프런트엔드가 응용의 글자를 지운다.
    pub fn backspace(&self) -> KeyOutcome {
        outcome(self.inner.lock().unwrap().backspace())
    }

    /// 현재 조합을 확정해 돌려주고 버퍼를 비운다(포커스 아웃 등).
    pub fn flush(&self) -> String {
        self.inner.lock().unwrap().flush()
    }

    /// 조합 버퍼를 확정 없이 비운다.
    pub fn reset(&self) {
        self.inner.lock().unwrap().reset()
    }

    /// 현재 조합 중 문자열.
    pub fn preedit(&self) -> String {
        self.inner.lock().unwrap().preedit()
    }

    /// 조합 중인 내용이 없는가.
    pub fn is_empty(&self) -> bool {
        self.inner.lock().unwrap().is_empty()
    }
}

#[cfg(test)]
mod tests;
