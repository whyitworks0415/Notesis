# 연속 필기 렌더링 최적화 (2026-10-03)

## 코드에서 확인한 비용

- Dense page의 bitmap key에 page revision과 stroke count가 포함되어 있었다. 새 획마다 cache miss가 발생하고, 펜을 뗀 직후 모든 획을 다시 software canvas에 그려 비트맵을 생성했다. 이전 revision 비트맵들도 48MiB LRU 안에 남았다.
- Cache 생성은 mesh refinement와 동일한 단일 worker를 사용했다. 필기를 다시 시작해도 cache 작업은 viewport 이동 전까지 계속될 수 있었다.
- 디버그 HUD의 문자열 state를 `NoteScreen`이 읽었다. 500ms마다 전체 노트 composable이 invalidation되어 AndroidView update와 toolbar 재계산까지 호출될 수 있었다.
- 줄/격자/점 배경은 확대하여 일부만 보이는 경우에도 페이지 전체 pattern을 draw call로 제출했다.
- 유리 UI spring ticker는 매 프레임 지난 300ms의 sample에 `zipWithNext/mapNotNull`을 반복해 Pair와 임시 list를 생성했다.

## 변경

- 비트맵은 page/scale별로 보관하고, 이전 비트맵의 획 prefix가 identity와 순서까지 일치하면 재사용한다. 뒤에 추가된 획만 기존 vector renderer로 그린다.
- 전체 비트맵 갱신은 마지막 편집 후 700ms 동안 쉬고 있을 때 수행한다. 새 획 표시와 저장은 이 대기를 거치지 않는다.
- 새로운 펜 접촉/편집/문서 교체와 viewport 이동은 진행 중인 비트맵 작업을 획 사이에서 중단시킨다. stale 결과는 게시하지 않는다.
- Undo/erase/move/load-at-front 또는 mesh replacement로 prefix가 바뀌면 즉시 vector rendering으로 돌아간다. 고배율/저배율 제한과 48MiB bitmap 한도는 유지한다.
- 형광펜이 있는 페이지는 multiply 합성 순서를 유지하기 위해 전체 비트맵 생성을 하지 않는다. 기존 코드에서 화면 밖 형광펜이 bitmap에 잘못 포함될 가능성도 차단했다.
- HUD의 state를 별도 composable에 옮겨 500ms 갱신을 해당 Text 안에서 처리한다. 표시 내용·위치·주기는 유지한다.
- 종이 pattern은 page 원점과 간격을 유지하면서 viewport 및 4 page-unit 여백 안의 항목만 제출한다.
- 유리 spring 계산의 sample 저장을 primitive ring buffer로 바꿨다. 기존 평균 가속도 계산, 300ms window, duplicate timestamp 처리와 spring 계수는 유지한다.
- 기존 획 보정 코드의 restricted `toImmutable()` 호출 두 곳은 동일 입력을 받는 공개 `Stroke` 생성자로 교체했다. 라이브러리 bytecode에서 생성자 내부의 immutable snapshot 처리를 확인했다.

UI 배치, 제스처, brush, stabilization/prediction, 필압, 획 저장 형식은 변경하지 않는다. 기존 작업 트리에 있던 PDF cache/페이지 로딩 수정도 보존한다.

## 검증

- `RenderPipelineTest`: append 재사용, undo/erase/reorder/prepend/replacement 무효화, 동일 geometry의 다른 identity 구분, 변경 없는 120 frame에서 prefix 검사 1회, pattern 정렬 및 가장자리 여백.
- `MotionSampleWindowTest`: 기존 list 계산과 2,000개 sample을 비교한다. 240Hz buffer 확장/순환, duplicate timestamp, 1초 pause와 clear를 포함한다.
- 기존 필기 안정화·prediction·저장·UI 관련 unit test도 함께 실행한다.

최종 소스의 unit test 119개가 모두 통과했고 debug/release APK 빌드와 `lintDebug`가 성공했다. Lint에는 기존 경고 59개와 hint 1개가 남아 있다. 최초 통합 검증은 PC의 Java native memory 부족으로 중단되었으며, 작업에서 생성한 잔여 compiler process를 정리하고 단일 worker/제한된 heap으로 재검증했다.

## 실기기 비교

연결된 Android 기기가 없어 wet-ink ms 감소량, 발열, 실제 GPU 합성 모습은 아직 측정하지 않았다. 아래는 후속 측정 절차이며 측정 결과가 아니다.

1. 같은 release 빌드 설정/기기/페이지/배율/brush에서 기존 빌드와 수정 빌드를 비교한다.
2. 500획 이상의 페이지에 연속 필기를 하고 wet ink, pen-up, page/dense draw p50/p95/p99와 allocation/GC를 기록한다.
3. HUD 표시 여부에 따른 차이도 비교한다. frame 항목은 frame 간격이며 CPU 처리시간이 아니므로 60Hz의 약 16.7ms 자체는 병목 판정 근거가 아니다.
4. Undo/redo/erase/lasso move, 형광펜 겹침, 확대된 점 배경의 viewport 가장자리, 필기 직후 pinch/페이지 전환을 확인한다.
5. 같은 밝기/refresh rate에서 10분 연속 필기 후 온도와 배터리 사용량을 비교한다.
