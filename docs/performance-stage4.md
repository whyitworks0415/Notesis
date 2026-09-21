# 4단계 — 긴 문서 / PDF 메모리 최적화

## 확인된 병목 후보

1. PDF source 하나가 `largeMemoryClass / 2`, 최대 256MB까지 사용할 수 있었습니다. 본문과 reference panel이 함께 열리면 cache 예산도 두 배가 됩니다.
2. 1024px preview와 2048px whole-page render가 같은 LRU를 사용해 서로를 밀어냈습니다.
3. cache는 byte 기준으로 제한됐지만 viewport에서 멀어진 page를 즉시 제거하지 않아 오래된 page가 예산을 계속 점유했습니다.
4. prefetch halo의 모든 PDF page를 현재 page와 같은 full width로 요청했습니다.
5. dense-page ink raster는 48MB LRU만 적용되어 page 이동 후에도 다른 page의 raster가 남았습니다.
6. custom template과 reference-panel image는 draw마다 `BitmapFactory.decodeFile()`을 다시 실행할 수 있었습니다.

## 적용한 메모리 모델

### PDF source별 총예산

- 일반 기기: `memoryClass / 8`
- low-RAM 기기: `memoryClass / 10`
- 최솟값 64MB, 최댓값 96MB
- 문서 page 수와 무관한 고정 byte ceiling

총예산은 역할별로 분리합니다.

- preview cache: 20%
- visible full-page cache: 40%
- visible tile cache: 나머지 40%

### Active page 정책

- 현재 viewport와 인접 halo만 preview cache에 유지
- 실제 viewport에 보이는 page만 full-page/tile cache에 유지
- 인접 prefetch page는 최대 1024px preview만 생성
- page set, visible full-resolution set 또는 zoom width bucket이 바뀌면 generation 갱신
- 이전 generation의 queued/running 결과는 3단계 정책에 따라 게시하지 않음

### 기타 bitmap resource

- dense ink raster: 48MB → 36MB, 현재 page ±1 밖의 항목 제거
- main note image cache: 48MB byte LRU 유지
- reference image cache: 24MB byte LRU 추가
- main/reference template cache: 각각 16MB byte LRU 추가
- composable 종료 시 cache strong reference 제거

cache에서 제거된 bitmap은 draw 중인 display list와의 recycle race를 피하기 위해 강제 recycle하지 않습니다. stale background render처럼 화면에 게시된 적 없는 bitmap만 즉시 recycle하고, 정상 cache eviction은 Android runtime이 안전하게 회수합니다.

## 긴 문서에서의 동작

- `Document`는 page metadata만 보유합니다.
- stroke/mask mesh는 기존 lazy page loader와 `KEEP_PAGES = 2` active 범위를 사용합니다.
- 멀어진 clean page는 stroke/mask/index를 비우고 필요할 때 디스크에서 다시 읽습니다.
- dirty page와 undo/redo가 참조하는 page는 데이터 안전성을 위해 유지합니다.
- 따라서 읽기·탐색 시 고비용 mesh/bitmap은 100~1000 page 전체가 아니라 viewport 주변과 byte budget에 의해 제한됩니다.

## 구조적 전후 차이

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| PDF source cache 상한 | 64~256MB | 64~96MB |
| whole-page 역할 | preview/full 혼합 | preview/full 분리 |
| 인접 page prefetch | 현재 zoom width | 최대 1024px preview |
| high-resolution 유지 범위 | byte LRU 전체 | 실제 visible page |
| tile 유지 범위 | byte LRU 전체 | 실제 visible page |
| dense ink cache | 48MB 전체-page LRU | 36MB + 현재 page ±1 |
| reference image | draw마다 decode 가능 | 24MB byte LRU |
| custom template | draw마다 decode 가능 | 16MB byte LRU |

## 테스트

- PDF 역할별 cache budget 합계가 총 byte ceiling과 정확히 일치
- memory class가 커져도 96MB 상한 유지
- full-resolution width는 visible page에만 적용
- 1000 page 입력에서도 full-resolution 대상은 viewport page 두 개뿐임
- page set, visible set, zoom bucket 변경 시 generation 갱신
- 1000 page document의 viewport 조회가 보이는 소수 page로 제한됨

## 실기기 확인 항목

- 100~1000 page PDF를 빠르게 처음→중간→끝으로 이동하며 PSS/Java heap 추이 확인
- 동일 동작을 여러 번 반복해 memory가 page 수에 따라 계속 증가하지 않는지 확인
- 1000% zoom과 fit-width를 반복해 full-page/tile cache가 상한 안에서 안정되는지 확인
- main note와 reference PDF를 동시에 열고 이동해 OOM 여부 확인
- image/custom template page를 반복 방문해 decode allocation과 GC 감소 확인
- 멀어진 page를 다시 방문했을 때 preview가 먼저 보이고 visible detail이 정상 복원되는지 확인
- app background/restore, undo/redo, 저장 후 재열기 확인

연결된 Android 기기가 없어 실제 PSS/OOM stress 수치는 포함하지 않았습니다. 1단계 HUD와 Android Studio Memory Profiler에서 0.31.4와 0.31.5를 동일 문서로 비교해야 합니다.
