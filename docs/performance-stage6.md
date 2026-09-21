# 6단계 Stroke Stabilization 재설계

## 기존 문제

- wet ink는 raw MotionEvent를 표시하고, `correctStrokeStart()`와 `smoothFreehandStroke()`는 pen-up 이후 committed stroke에만 적용했다.
- 따라서 화면에서 따라오던 궤적과 저장된 궤적이 달랐고 안정화 강도가 높을수록 pen-up 순간 변화가 커질 수 있었다.
- 시작점 보정은 첫 세 점만 사후 검사했으며 속도나 sample 간 `Δt`를 사용하지 않았다.
- smoothing은 고정된 3점 평균이라 느린 jitter와 빠른 필기를 구분하지 못했다.

## 새 파이프라인

```text
raw MotionEvent + historical samples
→ finite/time input sanitizer
→ adaptive streaming stabilizer
→ stable-direction prediction gate
→ wet ink
→ same trajectory commit / auto-shape recognition
```

- 안정화는 digitizer noise와 같은 좌표계인 screen pixel 단위로 처리하고 AndroidX Ink가 기존 transform으로 page 좌표로 변환한다.
- 속도(`distance / Δt`)가 빠르면 raw 좌표를 더 빠르게 따라가고, 느리고 작은 움직임에는 smoothing을 더 적용한다.
- 방향 cosine으로 corner/cusp를 검출해 90도 전환과 빠른 반전에서는 raw 좌표를 92% 이상 따라간다.
- 첫 8 sample의 큰 역방향 excursion은 outgoing 이동을 완화하고 복귀 sample을 빠르게 따라 start hook을 줄인다.
- prediction은 최소 4 sample과 두 번의 안정된 방향이 확인된 뒤에만 허용하며 corner/start reversal에서는 잠시 중단한다.
- lift hook/jump는 마지막 안정 좌표에서 끝내고 정상적인 마지막 이동은 endpoint를 88% 이상 따라간다.
- `ACTION_MOVE`와 `ACTION_UP`에 묶인 historical samples도 순서대로 동일한 filter에 전달한다.
- 0%에서는 MotionEvent를 재구성하지 않고 원본 입력을 그대로 wet ink와 commit에 전달한다.
- 좌표 배열은 재사용하며 안정화가 꺼진 기본 경로에는 새 hot-path allocation이 없다.

## 동작 보존

- 기존 stroke/page 저장 형식과 AndroidX Ink serialization은 변경하지 않았다.
- undo/redo는 동일한 committed Stroke를 사용한다.
- eraser, lasso, shape, mask 등 freehand 안정화가 필요 없는 도구 경로는 변경하지 않았다.
- auto-shape recognition은 이제 wet ink와 동일한 stabilized trajectory를 입력으로 받는다.

## 자동 테스트

`StrokeStabilizerTest`에서 다음을 검증한다.

- raw 직선과 jitter 직선
- 곡선 방향 및 endpoint
- 90도 corner와 빠른 반전
- dot과 짧은 stroke
- 첫 8 sample 안의 reverse spike
- pen-lift hook/jump 및 정상 continuation
- 빠른 필기가 느린 필기보다 raw를 더 가깝게 추종하는지
- 0/10/30/100%에서 0% exact pass-through와 단계적인 jitter 감소
- prediction 시작 지연과 cusp suppression
- NaN/Infinity driver sample 격리

## 기기 테스트

1. 안정화 0%에서 기존 필기 감각과 궤적이 동일한지 확인한다.
2. 10%와 30%에서 천천히 직선을 그려 미세 jitter가 줄고 지연감은 크지 않은지 확인한다.
3. 작은 한글, 영문, 숫자와 점을 반복해 시작점과 획 길이가 보존되는지 확인한다.
4. ㄱ/ㄴ 모양, 지그재그, 빠른 되돌림에서 corner가 둥글어지거나 prediction이 튀지 않는지 확인한다.
5. 펜을 비스듬히 빠르게 떼어 끝에 hook/jump가 생기지 않는지 확인한다.
6. 100%에서 강한 smoothing이 동작하되 pen-up 순간 선이 재배치되지 않는지 확인한다.
7. auto-shape를 켜고 직선/원 인식 결과가 화면에 보인 궤적과 일치하는지 확인한다.
