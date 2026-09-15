# 필기 렌더링 구조

## 1. 범위와 불변조건

**interaction 렌더링은 전체 획 수가 아닌 visible tile 수에 의해 제한된다.**

`DryLayer.prepareFrame()`은 페이지별 viewport와 캐시 소스로 `InkDrawPlan`을 만든다.
계획 입력에 `Page.strokes`가 없으며, 확정 필기의 벡터 fallback은 Android 경로에서 사용하지 않는다.
순수 정책은 정지 상태에 한해 사전 준비된 완전한 영역 최대 2개, 획 32개, outline vertex 4,096개를 허용한다.
현재 Android 구현은 정지 상태에도 bitmap coverage를 기다리는 보수적인 선택을 한다.

작업은 기존 `main`에서 수행했다. 기존 사용자 변경과 `deferDetail` 설정 동작을 유지한다.
저장 serialization, 파일 schema 및 `VectorPdfExporter`의 벡터 출력은 바꾸지 않았다.

## 2. 계획과 레이어

`InkRenderPolicy.kt`: 주소는 페이지 세션 / 레이어 / density 단계 / x / y로 구성된다.
512px 타일, 2px bleed, 최대 density 16을 유지한다. 후보 조회와 무효화에는 추가 AA 여유 1px을 둔다.

각 visible cell은 exact → 호환 stale → coarse → snapshot 순서로 한 소스만 선택한다.
다른 density의 실제 page bounds로 대응시키고 clip을 적용한다. 인접 타일 하나가 없어도 준비된 소스를 버리지 않는다.
프레임당 기본 필기 bitmap draw는 두 레이어를 합쳐 visible cell 수의 2배 이하다.

합성 순서는 배경/PDF → 형광펜 → 이미지/텍스트 → 일반 필기 → 마스크/선택 UI → front-buffered wet이다.
이미지나 텍스트 상자가 있다는 이유로 캐시를 끄지 않는다.

## 3. 변경 전달과 로드

`Page.inkStore`는 `MutableList<Stroke>` 인터페이스를 제공하는 `InkStrokeStore`다.
추가/삭제/교체 때 변경 전후 획과 bounds를 기록한다. `afterEdit()`은 해당 변경만 drain한다.
`Page.revision`은 저장 동기화용이며, 타일 키와 유효성에는 사용하지 않는다.
이미지/텍스트/마스크 변경은 획 이벤트가 없으므로 필기 generation을 올리지 않는다.

`InkDirtyRegionTracker`는 요청된 주소 중 bounds가 겹치는 타일만 변경한다.
이동 전후의 사각형을 따로 보관하므로 두 위치 사이를 무효화하지 않는다.
필기 레이어가 바뀌는 속성 교체는 양쪽 레이어에 이벤트를 낸다.

초기 디스크 로드는 worker가 목록과 공간 인덱스를 준비한 뒤 `Page.installInk()`로 설치한다.
로드 중 추가된 획은 기존 획 뒤에 유지한다. `unloadInk()`는 이벤트 이력까지 해제한다.
상세 geometry는 타일 worker 안에서 생성하며, zoom 때문에 저장 획이나 undo/redo 객체를 교체하지 않는다.

## 4. 스레드 경계

UI는 변경 이벤트, 주소/generation, cache 게시, 계획과 표시 좌표계를 소유한다.
공간 인덱스는 초기 준비 후 목록 변경으로 갱신되며 draw에서 자동 rebuild하지 않는다.
worker는 타일 bounds로 인덱스를 조회하고 후보 목록을 만들고 상세화/rasterization을 수행한다.
지우개 등의 편집 명령은 별도의 명시적 인덱스 조회를 할 수 있다. 이 조회는 frame planning에 포함되지 않는다.

새 획의 즉시 인계에 한해 UI가 이미 전달받은 변경 획만 기존 저해상도 레이어에 합성한다.
이 bitmap patch는 두 레이어의 새 snapshot으로 원자적으로 게시된다. 기존 전체 획 조회는 하지 않는다.
이 구현은 vector overlay를 누적하지 않으므로 프레임의 overlay vector 수는 0이다.
아직 floor가 없거나 공간 확보가 지연되면 기존 wet 획을 유지하고 coverage 게시 시 제거한다.

## 5. 편집과 화면 전환

dirty만으로 기존 bitmap을 삭제하지 않는다. floor가 새 편집을 포함하면 해당 편집을 누락한 stale/coarse는 선택에서 제외한다.
삭제/복원/이동/속성 변경은 이전 floor를 복사한 뒤 dirty 영역의 합집합을 한 번 clear하고,
그 영역에 겹치는 현재 획을 순서대로 다시 합성한다. 겹친 형광펜이 두 번 칠해지지 않도록 하나의 clip union을 사용한다.
두 레이어의 floor가 모두 준비된 시점에 교체한다. 단순 투명 지우개로 기존 획을 손상시키지 않는다.

올가미는 worker가 선택 제외 배경과 선택 획을 레이어별 bitmap으로 준비한다.
이동 frame은 준비된 bitmap 4개를 사용한다. 준비 전에는 기존 필기를 유지한다.

`InkCoverageGate`는 다음 viewport가 덮일 때까지 마지막 완료 계획과 표시 좌표계를 유지한다.
작은 준비 표시를 그리며, 새 viewport의 로드는 `deferDetail`과 무관하게 진행한다.
최초 화면에는 유지할 이전 화면이 없으므로 준비 표시를 보여 준다.

## 6. 스케줄러와 소유권

`InkTileScheduler`: raster worker 1개, 대기 최대 32개. executor에는 drain runnable 하나만 전달한다.
편집/coverage → 현재 viewport → 정지 halo 순으로 예약한다. 같은 우선순위에서는 최신 viewport와 이동 방향을 고려한다.
주소/버전 중복을 합치고, 멀어진 요청과 이전 generation을 취소한다. 취소된 같은 주소도 다시 예약할 수 있다.
실행 중 토큰을 확인하며 UI 게시 시 세션, 페이지 생존, lifecycle, 토큰과 generation을 재검증한다.

`InkMemoryBudget`은 기존 `RuntimeMemory.inkTileCacheBytes()` 한도를 사용한다.
detail/stale 50%, 보호 snapshot 25%, 교체/작업/선택 bitmap 25%를 예약한다.
기본 floor는 긴 변 256px인 두 레이어 snapshot이다. 교체 공간은 이전 화면이 참조 중인 bitmap과 새 결과가 공존하기 위한 공간이다.
새 할당 전에 예약하고, cache·작업 중 결과·UI 게시 대기·이전 화면 참조를 소유권으로 계산한다.
표시 후 이전 소유자가 해제되면 교체 결과를 해당 cache pool로 이동한다.
화면 전체 detail이 예산에 들어오지 않으면 요청 density를 낮추고 보호 floor로 덮는다.

미게시 bitmap은 취소/실패/게시 거절 시 정확히 한 번 recycle한다.
게시된 bitmap은 마지막 cache/화면 참조 해제 후 GC에 맡긴다. 공개한 bitmap을 즉시 recycle하지 않는다.
페이지 제거, 문서 교체, trim, detach는 토큰과 참조를 해제한다. 숨김 trim 후 복귀는 coverage 준비를 다시 수행한다.

## 7. 자동 검증과 fixture

기존 66개와 신규 21개를 합쳐 JVM 17개 클래스·87개 테스트가 debug/release 모두 통과했다.

`InkRenderingTest`: 정책/dirty/순서/overlay clip/스케줄러/소유권/coverage/편집 인덱스 13개 테스트.
`InkScalingTest`: 실제 공간 인덱스 준비를 사용하는 4개 획 수 × 정상/부분 누락, 총 8개 테스트.
모든 획이 네 visible cell에 겹치는 fixture이며 단순 count 인자만 바꾸지 않는다.
준비 후 원본 collection 접근을 금지하는 spy를 사용하고 100회 interaction 계획을 만든다.

| 획 수 | 준비 단계 후보 수 | visible cells | 전체 준비 소스 | 한 타일 누락 소스 | interaction vector / fallback |
|---:|---:|---:|---:|---:|---:|
| 500 | 2,000 | 4 | 8 | 7 | 0 / 0 |
| 1,500 | 6,000 | 4 | 8 | 7 | 0 / 0 |
| 5,000 | 20,000 | 4 | 8 | 7 | 0 / 0 |
| 10,000 | 40,000 | 4 | 8 | 7 | 0 / 0 |

준비 이후 계획 생성의 원본 collection 조회와 UI 후보 복사는 모든 경우 0이다.
이 수치는 JVM 구조 검증 결과이며 Android frame time 측정치가 아니다.

`InkRenderingRegression`은 기존 `CustomizationInstrumentation`에 연결되어 실제 bitmap의 레이어, 경계, alpha,
추가와 삭제 전환을 검사한다. 기존 저장/텍스트/undo/redo/PDF 검사는 유지했다.
`InkFixtureActivity`는 debug/benchmarkRelease에만 포함한다. 네 획 수, 긴 곡선, 혼합 펜,
이미지/텍스트, 3개 페이지를 준비한다.
`InkRenderingBenchmark`는 확대/축소/드래그/플링/빠른 페이지 전환을 `FrameTimingMetric`으로 기록한다.
debug `InkRenderStats`에는 hit/missing, 예약/취소/dedupe, raster trace/time, cache bytes/eviction 및 계획 중 collection 접근을 기록한다.
release 통계 구현은 inline no-op이며 로그 문자열, trace와 clock 호출을 생성하지 않는다.

## 8. 검증 명령과 남은 기기 검증

```powershell
.\gradlew.bat --gradle-user-home .gradle-verification test
.\gradlew.bat --gradle-user-home .gradle-verification :app:testDebugUnitTest
.\gradlew.bat --gradle-user-home .gradle-verification :app:lintDebug
.\gradlew.bat --gradle-user-home .gradle-verification :app:assembleDebug
.\gradlew.bat --gradle-user-home .gradle-verification :app:assembleDebugAndroidTest
.\gradlew.bat --gradle-user-home .gradle-verification :benchmark:assembleAndroidTest
.\gradlew.bat --gradle-user-home .gradle-verification :benchmark:assembleBenchmarkRelease
.\gradlew.bat --gradle-user-home .gradle-verification :app:assembleBenchmarkRelease
```

위 8개 Gradle task는 2026-09-15에 통과했다. `git diff --check`도 통과했다.

2026-09-15: `adb devices`에 연결된 기기가 없다. native 시각 회귀, instrumentation 실행,
Macrobenchmark frame time의 p50/p95/p99 및 jank 전후 비교는 아직 실행하지 않았다.
APK 빌드 성공을 실제 기기 렌더링 성공으로 해석하지 않는다.

연결 후 `:app:connectedDebugAndroidTest`, `:benchmark:connectedBenchmarkReleaseAndroidTest`를 실행한다.
Macrobenchmark JSON과 Perfetto의 `Notesis.InkRaster` debug trace를 보관하고 동일 기기/해상도/열 상태에서 기존 버전과 비교한다.
현재 native 검사는 대표 장면을 검사하며 대규모 이동·trim 경쟁·여러 density의 시각적 일치까지 기기에서 확인해야 한다.
정량 성능 개선율은 해당 측정이 끝난 뒤 보고한다.
