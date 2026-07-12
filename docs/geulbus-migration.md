# geulbus-core 엔진 이행 설계

teogeul의 한글 조합 엔진(`HangulEngine`)을
[geulbus-core](https://crates.io/crates/geulbus-core)로 교체하기 위한 설계 문서.
배경은 [geulbus#2](https://github.com/chaotic-ground/geulbus/issues/2),
[hanmo#1](https://github.com/chaotic-ground/hanmo/issues/1) 참고.

## 전체 구조

```
LayoutData.kt (자판 데이터, Kotlin에 유지)
      │  jamoTable/jamoSet/combination 를 그대로 FFI로 전달
      ▼
rust/teogeul-engine (UniFFI 래퍼 크레이트)
      │  teogeul 테이블 → 날개셋 Layout(값-식) 변환
      ▼
geulbus-core::Engine  press(ascii, shift) → KeyOutcome
```

- 자판 데이터는 Kotlin(`LayoutData.kt`)에 남긴다. 엔진 생성 시 테이블을 FFI로
  넘기고, Rust 래퍼가 geulbus-core의 `Layout`(값-식 기반)으로 변환한다.
- 키 처리: `KeyEventHandler`는 물리 키를 QWERTY ASCII로 정규화해
  `press(ascii, shift)`를 부르고, `KeyOutcome { commit, preedit, consumed,
  delete_before }`를 `InputConnection`에 반영한다.

## 테이블 변환 규칙

teogeul 행 `[key, normal, shift]` (key = 비시프트 ASCII):

- geulbus `Layout.keys`는 시프트 적용된 ASCII로 인덱싱하므로 행 하나를
  두 항목으로: `keys[key] = normal`, `keys[shift(key)] = shift 값`
  (`shift(key)` = 대문자 또는 기호 시프트 짝).
- 값 변환 (`v & 0xffff` 로 preserveState 플래그(0x10000) 제거):
  - `0x1100..0x11FF` 첫가끝 자모 → `H3|` 낱자 (영역으로 초/중/종 판정)
  - `0x3131..0x3163` 호환 자모 (두벌식) → 자음은 이중 배치
    `T==2 ? 종성 : 초성`, 모음은 중성. ㄸ/ㅃ/ㅉ처럼 종성이 없는 자음은 항상 초성.
  - 그 외 (ASCII 문자) → 정수 리터럴 (조합 확정 후 그 문자 입력)
- preserveState 플래그는 버린다. teogeul에서는 자모 입력 후 상태 테이블 유지를
  위한 것이었지만, geulbus의 T는 음절 내용에서 유도되므로 불필요하다.
- key 128 행(온점/반점 특수키)은 `KeyEventHandler`가 엔진 앞에서 직접
  처리하므로 변환하지 않는다.

### jamoSet (상태 의존 자판: 신세벌식, 3-2015 계열)

teogeul의 상태는 "마지막 입력의 갈래"(0=없음, 1=초성, 2=중성, 3=종성)이고 상태별
테이블 4개를 갖는다. geulbus 값-식 변수로 같은 정보를 유도한다:

- `T` (0=비어있음, 1=초성/홑낱자만, 2=중성 있음) ≈ 상태 0/1 구분
- `F` (조합 중 종성 서열, 없으면 0) ≈ 상태 2(중성 뒤, F==0)와 3(종성 뒤, F>0) 구분

키별 식: `T==0 ? t0 : T==1 ? t1 : F ? t3 : t2` (tN = 테이블 N의 그 키 값,
같으면 축약). 검증된 대응:

| teogeul 상태 | 조건 | 예 (3-2015) |
|---|---|---|
| 0 (초기) | `T==0` | CHOJUNG |
| 1 (초성 뒤) | `T==1` | CHOJUNG |
| 2 (중성 뒤) | `T==2 && F==0` | CHOJONG_D (시프트=겹받침) |
| 3 (종성 뒤) | `T==2 && F>0` | CHOJONG (시프트=갈마들이 중성) |

알려진 극단 사례: 홑종성만 조합 중일 때(중성 없이 받침 먼저) teogeul은 상태
3(CHOJONG), T 기반은 T==1(CHOJUNG)을 쓴다. 실사용에서 도달하기 어려운 상태라
동작 차이를 허용한다 (major 업데이트).

### 조합 테이블

행 `[a, b, 결과]` → `Layout.combine[(갈래(a), a, b)] = 결과`. 갈래는 코드포인트
영역으로 판정하고, preserveState 플래그가 붙은 행은 플래그 제거 후 중복 제거한다.

## geulbus-core에 필요한 업스트림 변경 (0.3)

1. **KeyTable 평가 문맥에 D/E/F 추가**: `press()`가 `Ctx { t, p }`만 넘기는데,
   조합 중 초/중/종성 서열(D/E/F)도 넘긴다. 이미 오토마타 경로에서 쓰는
   `slot_seq()`를 재사용하는 작은 변경. jamoSet 상태 2/3 구분에 필요.
2. **두벌식 도깨비불**: 휴리스틱 경로 `feed_jung()`이 CVC 뒤 모음에서 음절을
   통째로 확정하고 홀소리 음절을 새로 시작한다(세벌식 동작). 두벌식은 종성의
   마지막 자음을 떼어 새 음절의 초성으로 넘겨야 한다(간+ㅏ→가나, 앉+ㅏ→안자).
   `Layout`에 두벌식 표시를 추가하고, 켜져 있으면 분리 로직(0x12 도깨비불 명령과
   같은 원리)을 자동 적용한다. 겹받침 분해는 조합 테이블 역방향 조회.

teogeul은 0.3 출시 전까지 git 의존으로 참조한다.

## Kotlin 쪽 변경

- `HangulEngine`, `HangulJamo`(엔진 내부용 상수) 제거. `LayoutData`는 유지.
- `KeyEventHandler`: `inputCode`/`inputJamo` 2단 호출 대신 `press` 1회.
  시프트 기호 역변환(SHIFT_CONVERT)은 "시프트 적용된 ASCII를 만든다"로 단순화.
- 백스페이스: `engine.backspace()` → `KeyOutcome` 반영 (`delete_before`는
  `deleteSurroundingText`).
- 모아치기 설정 제거 (major): `hardware_use_moachigi`, `hardware_full_moachigi`,
  `hardware_full_moachigi_delay`. geulbus의 순서 무관 조합이 대체한다.
- 영문 대체배열(드보락/콜맥) 변환(`LayoutConverter`)은 엔진과 무관하므로 유지.

## 검증

- Rust 래퍼 단위 테스트: 변환기(테이블→Layout)와 조합 시나리오
  (두벌식 도깨비불, 신세벌식 갈마들이, 3-2015 상태 구분, 겹받침).
- 기존 `KeyEventHandlerTest`를 새 엔진으로 이식. Robolectric(JVM) 테스트에서
  호스트용 cdylib를 로드한다.
- 알려진 의도적 동작 변화: 모아치기 타이밍 제거, 홑종성 상태의 jamoSet 선택,
  미완성 낱자 preedit 표기(첫가끝/호환 자모 렌더링 차이 가능).
