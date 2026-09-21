# Notesis 성능 최적화 TODO

## 1단계 — 성능 기준선과 병목 측정 ✅

Notesis 프로젝트의 성능 최적화를 시작하기 전에 현재 병목을 측정할 수 있는 기반부터 만든다.

### 중점

- stylus input → wet ink 표시 latency
- pen-up → stroke commit/finalize 시간
- mesh/refine 시간
- PDF page render 시간
- dense page draw 시간
- 가능하면 p50/p95/p99 확인
- hot path에서 allocation/GC가 많이 발생하는 곳 확인

### 제약 및 완료 조건

- 기존 동작과 저장 포맷은 변경하지 않는다.
- 측정 코드는 release 성능에 영향을 최소화하도록 debug/diagnostic 중심으로 구현한다.
- 완료 후 실제로 발견한 병목 후보를 우선순위별로 정리한다.
- 테스트/build를 실행한다.

---

## 2단계 — Stylus 입력 Hot Path 최적화

Notesis의 stylus 입력 경로를 최적화한다.

### 중점

- `InkCanvasView`의 `ACTION_DOWN`/`MOVE`/`UP` hot path 분석
- `MotionEvent` historical samples가 제대로 활용되는지 확인
- 불필요한 객체 생성/recycle/collection 할당 최소화
- 불필요한 invalidate와 Compose state update 최소화
- eraser/lasso처럼 고빈도 입력이 필요 없는 도구는 처리량 최적화 검토
- prediction 자체의 동작 방식은 유지

### 중요

- stroke stabilization 알고리즘은 아직 변경하지 않는다.
- 필기 결과와 저장 포맷은 그대로 유지한다.
- 실제 성능 개선 근거가 있는 변경만 적용한다.
- 테스트와 benchmark를 돌리고 변경 전후 차이를 정리한다.

---

## 3단계 — Render / Refine 파이프라인 최적화

Notesis의 stroke/PDF rendering과 refinement 파이프라인을 최적화한다.

### 중점

- render/refine 상태를 명확히 관리
- zoom이나 page 이동 후 obsolete background 작업이 결과를 덮어쓰지 않도록 generation token 또는 cancellation 적용
- 같은 stroke/page에 중복 render 작업이 발생하지 않게 하기
- interactive zoom/scroll 중에는 필요한 최소 품질만 렌더링
- idle 이후 high-quality refinement 수행
- viewport 밖 객체의 불필요한 재렌더 방지

### 제약 및 완료 조건

- 가능하면 `Dirty / Rendering / ViewportCached / Complete` 같은 명확한 상태 모델을 사용하되 과도한 리팩터링은 피한다.
- 기존 화면 결과와 저장 형식은 유지한다.
- 테스트/build를 실행한다.

---

## 4단계 — 긴 문서 / PDF 메모리 최적화

긴 노트와 큰 PDF에서 메모리 사용량과 스크롤 성능을 최적화한다.

### 중점

- 현재 viewport와 인접한 소수 페이지에만 고비용 bitmap/render resource 유지
- 멀어진 페이지의 PDF bitmap/cache/resource 해제
- page virtualization 또는 bounded active-page cache 적용
- bitmap cache를 byte budget 기준으로 관리
- thumbnail과 full-resolution render cache 역할 분리
- 100~1000페이지 문서에서도 메모리가 페이지 수에 거의 비례해서 증가하지 않게 설계

### 제약 및 완료 조건

- 현재 이미 있는 page loading/cache 구조를 먼저 활용하고 중복 시스템을 만들지 않는다.
- OOM, 빠른 페이지 이동, zoom 반복에 대한 regression test도 검토한다.

---

## 5단계 — 저장 / 편집 작업 최적화

Notesis의 편집 후 저장 경로를 분석하고 UI thread 부하를 줄인다.

### 중점

- `afterEdit()` 및 autosave 호출 빈도 확인
- 연속 필기 중 저장 요청을 debounce/coalesce
- 짧은 시간 내 여러 수정은 하나의 background save로 합치기
- 앱 background/노트 닫기 시 pending save 즉시 flush
- 같은 데이터를 불필요하게 반복 serialize하지 않도록 하기
- undo/redo 동작은 그대로 유지

### 제약 및 완료 조건

- 데이터 손실 위험이 없도록 lifecycle을 특히 주의한다.
- 기존 파일 포맷은 변경하지 않는다.
- 테스트/build 후 저장 안정성과 성능 개선 내용을 정리한다.

---

## 6단계 — Stroke Stabilization 재설계

현재 `correctStrokeStart()`와 pen-up 후 `smoothFreehandStroke()` 방식의 문제를 먼저 분석하고, 가능하면 하나의 실시간 입력 안정화 pipeline으로 통합한다.

### 목표 구조

```text
raw stylus input
→ input sanitizer
→ adaptive streaming stabilizer
→ prediction
→ wet ink
→ committed stroke
```

### 요구사항

- 작은 고주파 jitter는 줄이기
- 실제 corner/급격한 방향 전환은 보존
- 속도, Δt, 이동 거리 기반 adaptive smoothing
- 첫 4~8 sample에서 start spike/outlier 검출
- pen-lift hook/jump 처리
- 방향이 안정되기 전 prediction 제한
- wet ink와 최종 committed stroke의 궤적 차이 최소화
- stabilization 5~30%에서도 의미 있는 효과
- 0%에서는 기존 raw 입력과 최대한 동일
- 작은 글씨, dot, 짧은 stroke를 망가뜨리지 않기

Xournal++의 deadzone, velocity-aware smoothing, cusp preservation 아이디어는 참고해도 되지만 그대로 복사하지 않고 Android/S Pen 입력에 맞게 구현한다.

### 테스트

- 직선
- jitter 직선
- 곡선
- 90도 corner
- 빠른 방향 전환
- dot/짧은 stroke
- start reverse spike
- pen-lift jump
- 빠른/느린 필기
- 0/10/30/100% stabilization

auto-shape recognition도 가능하면 stabilized trajectory를 기준으로 판단하도록 검토한다.

---

## 7단계 — 최종 성능 회귀 점검

앞의 최적화들을 전체적으로 다시 점검한다.

### 중점 시나리오

- 빠른 연속 필기
- 500~2000 stroke dense page
- 긴 PDF
- 빠른 zoom/pan
- 연속 erase/lasso
- page 이동 반복
- 앱 background/restore
- undo/redo 반복

### 1단계 기준선 대비 비교 항목

- input latency
- frame/render time
- pen-up finalize
- PDF render
- memory usage
- GC/allocation
- 저장 시간

### 완료 조건

- 측정상 효과가 없거나 복잡도만 증가한 최적화는 제거한다.
- 마지막으로 `testDebugUnitTest`, lint, debug/release build 가능한 범위를 모두 실행한다.
- 실패가 있으면 원인까지 수정한다.
