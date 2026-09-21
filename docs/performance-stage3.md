# 3단계 — Render / Refine 파이프라인 최적화

## 확인된 병목과 경합

1. mesh refine은 generation을 작업 시작과 게시 시점에만 확인했습니다. 이미 시작한 dense-page rebuild는 viewport가 바뀐 뒤에도 끝까지 단일 worker를 점유했습니다.
2. PDF 요청의 generation이 바뀌어도 실행 중이던 render 결과는 `closed` 여부만 검사해 cache에 들어갈 수 있었습니다.
3. 이전 generation의 pending 항목이 현재 page에 남아 있으면 새 generation 요청을 억제할 수 있었습니다.
4. dense-page bitmap은 page가 viewport 밖으로 이동한 뒤에도 끝까지 생성하고 cache에 게시했습니다.
5. pinch는 detail을 보류했지만 느린 한 손가락 pan은 debounce 시간이 지나면 refine 또는 PDF detail 작업을 시작할 수 있었습니다.

## 적용한 상태 모델

페이지의 생성 리소스 상태를 저장 데이터와 분리해 다음 네 단계로 관리합니다.

- `Dirty`: source나 target viewport가 바뀌었거나 page data가 아직 load되지 않음
- `Rendering`: 현재 generation의 mesh 작업이 진행 중
- `ViewportCached`: base mesh 또는 현재 viewport 일부만 준비됨
- `Complete`: 현재 target scale에서 page 전체가 준비됨

이 값은 runtime 전용이며 note metadata와 stroke 저장 형식에 포함되지 않습니다.

## 적용한 변경

- 새 refine 요청은 이전 `Future`를 취소하고 generation을 증가시킵니다.
- stroke mesh rebuild는 stroke마다 interruption과 generation을 확인해 obsolete 작업을 빠르게 중단합니다.
- refine 결과는 generation과 tessellation target이 모두 일치할 때만 UI page에 적용합니다.
- 한 손가락 pan, pinch, fling 중에는 cached/base 품질만 사용하고 정지 후 한 번만 prefetch와 high-quality refine을 재개합니다.
- PDF priority generation에 page set뿐 아니라 width bucket도 포함했습니다.
- stale PDF whole-page bitmap과 tile은 cache에 넣지 않고 즉시 recycle합니다.
- stale pending 요청은 현재 generation의 같은 key 요청을 더 이상 막지 않습니다.
- dense-page bitmap은 viewport generation을 stroke마다 확인하고, 화면 밖으로 나간 결과는 게시하지 않습니다.
- 같은 page/key의 현재 generation 요청은 기존 pending set/map으로 계속 합칩니다.

## 구조적 전후 차이

| 경로 | 변경 전 | 변경 후 |
|---|---|---|
| obsolete mesh refine | page snapshot 전체 완료 후 폐기 | stroke 경계마다 취소 확인 |
| refine 중복 | debounce callback만 교체 | callback 교체 + 실행 중 Future 취소 |
| stale PDF 결과 | cache에 들어갈 수 있음 | generation 불일치 시 recycle |
| stale pending key | 새 generation 요청을 억제할 수 있음 | generation이 다르면 새 요청 허용 |
| pan/fling 품질 | detail request 가능 | cache/base만 사용, idle 후 refine |
| offscreen dense bitmap | 끝까지 생성·게시 가능 | 생성 중단, 게시 전 visibility 재검증 |

## 회귀 테스트

- stale pending generation과 현재 generation의 dedupe 구분
- current/open generation만 결과를 게시하는 조건
- `Dirty / ViewportCached / Complete` 상태 결정
- pan, pinch, fling 및 detail option의 idle 전환

## 실기기 확인 항목

- 500~2,000 stroke page에서 zoom/pan을 반복한 뒤 refine p95/p99와 frame p95/p99 비교
- zoom 직후 다른 page로 이동했을 때 이전 page가 뒤늦게 화면을 갱신하지 않는지 확인
- 빠른 page scrub 후 멈춘 page의 PDF가 먼저 선명해지는지 확인
- pinch/pan 중에는 기존 bitmap이 부드럽게 확대되고, 손을 뗀 뒤 선명한 mesh/tile로 바뀌는지 확인
- dense page를 벗어난 직후 allocation과 CPU 사용이 빠르게 감소하는지 확인
- PDF 위 ink, highlighter, masking tape, undo/redo 결과가 이전 release와 같은지 확인

연결된 Android 기기가 없어 실기기 latency 수치는 포함하지 않았습니다. 1단계 진단 HUD에서 0.31.3과 0.31.4를 같은 문서·zoom 동작으로 비교할 수 있습니다.
