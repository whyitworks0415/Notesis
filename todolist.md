# Notesis 기능·성능 개선 TODO

## 공통 원칙

- 정확도와 입력 지연 개선을 최우선으로 한다.
- 기존 노트, PDF, 녹음 파일 및 저장 형식과의 호환성을 유지한다.
- 필기 hot path에서 불필요한 allocation, serialization, I/O 및 전체 화면 갱신을 피한다.
- 백그라운드 작업 실패가 필기 입력이나 데이터 저장을 방해하지 않도록 격리한다.
- 60Hz와 120Hz 환경을 모두 대상으로 측정하고 검증한다.
- 변경 전 기준선을 먼저 기록하고, 변경 후 같은 조건에서 비교한다.
- 측정상 효과가 없거나 유지보수 복잡도만 크게 증가시키는 최적화는 제거한다.

---

## 1. 60Hz 필기 체감 개선

### 분석

- [x] 현재 `androidx.ink`, `InProgressStrokesView`, `MotionEventPredictor` 사용 구조와 input-to-display 경로를 문서화한다.
- [ ] input → wet ink 표시 구간별 latency와 프레임 경계를 실기기에서 측정한다. (릴리스 PC에 연결된 Android 기기 없음)
- [x] Android low-latency/front-buffer 경로가 지원 기기에서 올바르게 선택되도록 구성되어 있는지 확인한다.

### 구현

- [x] input → wet ink 표시 latency를 최소화한다.
- [x] 첫 2~3개의 안정적인 이동 벡터가 확보된 뒤에만 prediction을 활성화한다.
- [x] prediction lead를 4/6/9ms로 비교하거나 기기·주사율별로 선택할 수 있게 설계한다.
- [x] 속도, 곡률, 방향 변화량을 기준으로 급회전 또는 저속 구간의 prediction을 자동 감소하거나 비활성화한다.
- [x] 마지막 predicted 구간만 별도의 transient ink head로 관리한다.
- [x] 빠른 필기에서만 아주 짧은 translucent/soft edge를 적용해 60Hz 프레임 간격을 시각적으로 완화한다.
- [x] 전체 stroke에는 blur를 적용하지 않는다.
- [x] 실제 입력이 도착하면 predicted/soft 구간을 즉시 실제 geometry로 교체한다.
- [x] prediction과 시각 효과가 저장되는 stroke geometry에 영향을 주지 않게 분리한다.
- [x] 과도한 blur나 길게 끌리는 trail이 생기지 않도록 길이와 수명을 제한한다.
- [x] 60Hz와 120Hz에서 각각 자연스럽게 동작하도록 주사율·입력 속도 기반 정책을 적용한다.

### 검증

- [ ] 변경 전후 input-to-display latency를 동일 조건에서 측정한다.
- [ ] 60Hz/120Hz의 빠른 필기, 저속 필기, 작은 글씨, 급회전에서 정확도와 체감 지연을 비교한다.
- [ ] predicted head가 실제 geometry로 교체될 때 튐, 잔상, 이중 잉크가 없는지 확인한다.

---

## 2. Stroke Stabilization 재설계

현재 `correctStrokeStart()`와 pen-up 후 `smoothFreehandStroke()` 중심 구조를 분석하고 다음 streaming pipeline으로 통합한다.

```text
raw stylus input
→ sanitizer
→ adaptive stabilizer
→ short prediction
→ wet ink
→ committed stroke
```

### 분석 및 설계

- [ ] `correctStrokeStart()`와 `smoothFreehandStroke()`가 wet/final geometry 차이, 시작점 보정, pen-up 지연에 미치는 영향을 측정한다.
- [ ] Xournal++의 deadzone, velocity-aware Gaussian, cusp preservation 아이디어를 참고하되 Android/S Pen 입력 특성에 맞게 설계한다.
- [ ] sanitizer, 위치 안정화, pressure 안정화, prediction, commit 단계를 독립적으로 테스트할 수 있게 분리한다.

### 구현

- [ ] 작은 고주파 jitter를 제거한다.
- [ ] 의도적인 corner와 급회전을 보존한다.
- [ ] 속도, 이동 거리, `Δt` 기반 adaptive smoothing을 적용한다.
- [ ] 첫 4~8 sample에서 start spike/outlier를 판정하고 안전하게 보정한다.
- [ ] pen-lift 직전 hook/jump를 판정하고 처리한다.
- [ ] 작은 글씨, dot, 짧은 stroke를 보존한다.
- [ ] stabilization 0%는 거의 raw 입력과 동일하게 유지한다.
- [ ] 5~30% 구간에서도 차이를 느낄 수 있도록 strength mapping을 개선한다.
- [ ] wet stroke와 pen-up 후 committed stroke 모양의 차이를 최소화한다.
- [ ] auto-shape recognition이 stabilized trajectory를 기준으로 동작하는 편이 적절한지 검토하고 회귀 테스트한다.
- [ ] pressure smoothing을 위치 smoothing과 별도 정책으로 처리한다.

### 테스트

- [ ] 직선 및 jitter 직선
- [ ] 곡선
- [ ] 90도 corner 및 빠른 방향 전환
- [ ] 짧은 stroke 및 dot
- [ ] start spike/outlier
- [ ] lift spike/hook/jump
- [ ] 빠른 필기 및 느린 필기
- [ ] stabilization 0/5/10/20/30/100%

---

## 3. 렌더링 / 메모리 / 긴 문서 최적화

### 렌더링 파이프라인

- [ ] obsolete background render/refine 작업에 cancellation 또는 generation token을 적용한다.
- [ ] 같은 page/stroke에 대한 중복 rendering과 refinement를 방지한다.
- [ ] interactive zoom/pan 중에는 coarse rendering을 사용한다.
- [ ] interaction 종료 후 idle 시점에 high-quality refinement를 수행한다.
- [ ] 오래된 작업 결과가 새 viewport 또는 새 generation을 덮어쓰지 못하게 한다.

### 메모리 및 캐시

- [ ] viewport와 인접한 페이지만 고비용 bitmap, mesh, render resource를 유지한다.
- [ ] 먼 페이지의 PDF bitmap과 render cache를 적극적으로 해제한다.
- [ ] 캐시를 entry 수가 아닌 byte budget 기준으로 제한한다.
- [ ] PDF thumbnail cache와 full-resolution cache를 분리한다.
- [ ] 100~1000페이지 PDF에서도 메모리 사용량이 전체 페이지 수에 비례해 계속 증가하지 않게 한다.

### Dense page 및 hot path

- [ ] dense page에서 불필요한 전체 stroke iteration을 제거한다.
- [ ] `StrokeGrid`가 실제 병목인지 benchmark/profile로 먼저 확인한다.
- [ ] 측정 결과가 필요성을 입증할 때만 다른 spatial index를 검토한다.
- [ ] draw/render hot path의 임시 객체, collection, bitmap allocation과 GC를 최소화한다.

### 검증

- [ ] 100/500/1000페이지 PDF로 스크롤, 페이지 점프, zoom/pan을 반복한다.
- [ ] 페이지당 500/1000/2000 stroke에서 draw, hit-test, erase, lasso 성능을 측정한다.
- [ ] frame time, p95/p99 frame time, memory peak, GC 빈도, PDF render 시간을 비교한다.
- [ ] 기존 화면 결과와 저장 형식이 유지되는지 확인한다.

---

## 4. 저장 / Autosave 최적화

### 분석 및 구현

- [ ] `afterEdit()` 및 serialization 호출 경로, 빈도, 실행 thread를 분석한다.
- [ ] 연속 필기 중 save 요청을 debounce/coalesce한다.
- [ ] 짧은 시간 안의 여러 stroke 편집을 하나의 background save로 합친다.
- [ ] UI thread에서 파일 I/O와 큰 serialization을 제거한다.
- [ ] 앱 background, 노트 닫기, process lifecycle 변화 시 pending save를 즉시 flush한다.
- [ ] undo/redo와 데이터 안정성을 유지한다.
- [ ] 같은 페이지를 반복 serialize하지 않도록 dirty-page 기반 저장을 검토하고, 호환성과 원자성을 보장할 수 있을 때 적용한다.
- [ ] save 실패·취소·재시도 정책을 명확히 하고 필기 UI와 분리한다.

### 데이터 안정성 및 테스트

- [ ] 연속 필기 중 강제 background/foreground 전환
- [ ] 노트 닫기 직전 pending save flush
- [ ] process 종료 및 복원
- [ ] 빠른 undo/redo 후 저장
- [ ] 동일 페이지 연속 편집과 여러 페이지 교차 편집
- [ ] 저장 실패, 디스크 공간 부족, 중단 후 재시도
- [ ] save 시간과 필기 중 frame time의 변경 전후 비교

데이터 손실 방지를 최우선 완료 조건으로 둔다.

---

## 5. 필기 검색 / OCR 기능

현재 포함된 ML Kit Digital Ink Recognition과 기존 document/page 구조를 우선 활용한다.

```text
stroke/page 변경
→ idle/background recognition
→ page별 OCR 결과 저장
→ SQLite/Room FTS index
→ 전체 노트 검색
```

### 인식 계층

- [ ] recognition 결과와 작업 상태를 필기 저장 계층에서 독립시킨다.
- [ ] stroke마다 즉시 OCR하지 않고 page/영역 변경을 debounce한다.
- [ ] 변경된 영역 또는 페이지만 다시 인식한다.
- [ ] OCR 작업을 UI thread 밖에서 수행한다.
- [ ] 페이지별 인식 text, token, confidence, 대략적 bounds, model/version 정보를 저장할 구조를 설계한다.
- [ ] SQLite/Room FTS 기반 전체 노트 검색 index를 구축한다.
- [ ] OCR 실패, 취소, 모델 미설치가 필기 저장과 편집에 영향을 주지 않게 한다.

### UX 및 재사용

- [ ] 검색 결과에서 노트 / 페이지 / 위치로 바로 이동할 수 있게 한다.
- [ ] 검색된 단어가 있는 대략적인 영역을 highlight한다.
- [ ] 선택 영역 handwriting → text 변환도 같은 recognition pipeline을 재사용한다.
- [ ] 향후 수식 인식, AI 요약 등이 같은 결과 계층을 사용할 수 있게 인터페이스를 분리한다.

### 테스트

- [ ] 연속 필기 중 debounce/coalesce 동작
- [ ] 페이지 일부 변경 후 부분/페이지 재인식
- [ ] OCR background 작업 중 필기 frame time
- [ ] 앱 종료/재실행 후 index 복원 및 갱신
- [ ] 인식 실패·취소·재시도와 저장 데이터 무결성

---

## 6. 음성 녹음 ↔ 필기 동기화

### 데이터 모델

- [ ] 녹음 중 생성되는 stroke마다 recording-relative timestamp를 기록한다.
- [ ] 녹음 중 page 이동을 timeline event로 기록한다.
- [ ] 여러 녹음 세션을 구분하고 각 세션의 시간 기준을 독립적으로 유지한다.
- [ ] stroke 저장 포맷을 크게 변경하지 않는 방안을 우선 검토한다.
- [ ] 필요하면 별도의 timeline metadata 파일 또는 테이블을 사용한다.
- [ ] 기존 노트와 기존 녹음 파일을 그대로 열고 재생할 수 있게 한다.

### 재생 및 UX

- [ ] 현재 오디오 시간과 연관된 stroke를 강조한다.
- [ ] stroke를 탭하면 연결된 녹음 시점으로 seek한다.
- [ ] timeline의 page 이동 event를 재생/navigation에 활용한다.
- [ ] 재생 중 전체 canvas를 계속 rerender하지 않고 최소한의 highlight overlay만 갱신한다.
- [ ] timestamp가 없는 기존 stroke는 기존 방식으로 안전하게 표시한다.

### 테스트

- [ ] 녹음하면서 빠른 필기 및 페이지 이동
- [ ] pause/resume 및 여러 recording session
- [ ] stroke 탭 seek와 재생 highlight 동기 정확도
- [ ] 긴 녹음 재생 중 frame time과 배터리/메모리 사용
- [ ] 기존 녹음·노트 호환성

---

## 7. PDF Reference Link 기능

현재 reference panel, PDF import, capture 기능과 navigation stack을 재사용한다.

### 데이터 및 동작

- [ ] PDF 또는 다른 노트의 영역을 붙일 때 `source document/note ID`, `source page`, `source bounds/position`을 함께 저장한다.
- [ ] reference object를 탭하면 원본 위치로 이동한다.
- [ ] reference object를 이동하거나 크기 변경해도 source link를 유지한다.
- [ ] 원본 PDF 자체를 복제하지 않는다.
- [ ] 원본을 찾을 수 없으면 일반 이미지처럼 안전하게 표시한다.
- [ ] 기존 image object 및 저장 포맷과의 호환성을 최대한 유지한다.
- [ ] reference panel과 같은 navigation stack을 사용해 원래 페이지로 돌아갈 수 있게 한다.

### 테스트

- [ ] PDF 영역 capture → 붙여넣기 → 원본 이동 → 돌아오기
- [ ] 다른 노트 reference 이동
- [ ] reference object 이동/크기 변경/복제 후 link 보존
- [ ] 원본 이동·삭제·접근 불가 시 fallback
- [ ] 기존 image object와 구버전 문서 로딩

---

## 8. 학습 모드 / 마스킹 강화

현재 mask 객체와 rendering 구조를 유지하고 학습 상태는 별도 metadata로 관리한다.

### 기능

- [ ] 페이지의 모든 mask 숨김/표시
- [ ] mask를 하나씩 탭해 답 공개
- [ ] 맞음 / 틀림 표시
- [ ] 판정 후 다음 mask로 자동 이동
- [ ] 세션 진행률 표시
- [ ] 틀린 항목만 다시 보기
- [ ] 노트별 간단한 학습 통계 저장

### 제약 및 테스트

- [ ] 별도의 복잡한 flashcard 시스템을 먼저 만들지 않는다.
- [ ] mask geometry/content와 session/statistics metadata를 분리한다.
- [ ] 학습 상태 갱신이 필기 rendering과 autosave 성능에 영향을 주지 않게 한다.
- [ ] mask 추가/삭제/이동 시 기존 학습 metadata를 안전하게 정리한다.
- [ ] 앱 종료/재실행, 페이지 이동, 세션 재개, 틀린 항목 재학습을 테스트한다.

---

## 9. 페이지 / 템플릿 UX 확장

### 페이지 레이아웃

- [ ] 세로 스크롤 / 가로 스크롤을 선택할 수 있게 한다.
- [ ] 2-page spread를 지원한다.
- [ ] 2-page mode에서도 실제 document 좌표와 저장 데이터는 변경하지 않고 viewport/layout 레벨에서 처리한다.
- [ ] 긴 문서 virtualization 및 주변 페이지 cache 정책과 통합한다.

### 템플릿 및 페이지 기능

- [ ] 사용자 template 관리 기능을 추가한다.
- [ ] PDF 또는 이미지에서 template를 생성할 수 있게 한다.
- [ ] 새 페이지 생성 시 template를 선택할 수 있게 한다.
- [ ] 최근 사용 template를 제공한다.
- [ ] 페이지 복제 기능을 추가한다.

### 테스트

- [ ] 세로/가로 전환 후 현재 페이지와 zoom 위치 유지
- [ ] 2-page spread의 홀수/짝수 페이지, 첫/마지막 페이지 처리
- [ ] 500+ 페이지에서 scroll mode 전환 및 virtualization 성능
- [ ] template 생성/선택/최근 목록/삭제와 원본 변경 처리
- [ ] 페이지 복제 후 stroke, image, mask, reference 및 metadata 무결성

---

## 10. 최종 회귀 / 성능 검증

### 필수 시나리오

- [ ] 60Hz 빠른 필기
- [ ] 120Hz 필기
- [ ] 작은 글씨
- [ ] 빠른 연속 필기
- [ ] 2000+ stroke 페이지
- [ ] 500+ 페이지 PDF
- [ ] zoom/pan 반복
- [ ] 연속 erase/lasso
- [ ] 녹음하면서 필기
- [ ] OCR background 작업 중 필기
- [ ] autosave 중 필기
- [ ] page 전환 반복
- [ ] 앱 background/restore

### 기준선 대비 비교

- [ ] input-to-display latency
- [ ] frame time
- [ ] p95/p99 frame time
- [ ] pen-up finalize 시간
- [ ] memory peak
- [ ] GC 빈도
- [ ] PDF render 시간
- [ ] save 시간

### 최종 완료 조건

- [ ] 모든 benchmark를 가능한 한 동일 기기, 문서, 입력 시나리오에서 변경 전 기준선과 비교한다.
- [ ] 효과가 없거나 복잡도만 크게 증가시킨 최적화를 제거한다.
- [ ] 기존 저장 형식, 화면 결과, 구버전 문서 호환성을 확인한다.
- [ ] unit test를 실행하고 실패 원인을 수정하거나 명확히 기록한다.
- [ ] lint를 실행하고 실패 원인을 수정하거나 명확히 기록한다.
- [ ] 가능한 범위의 debug/release build를 실행한다.
- [ ] 최종 결과에 측정값, 미실행 항목, 알려진 제한, 남은 기술 부채를 짧게 정리한다.
