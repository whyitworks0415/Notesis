# 5단계 저장 / 편집 성능 결과

## 확인된 병목

1. 기본 노트는 1.2초 quiet-period debounce를 사용했지만 참고 패널은 편집마다 즉시 `saveLater()`를 호출했다.
2. `saveLater()`는 최신 요청만 디스크에 쓰도록 합쳤지만, 합치기 전에 UI thread에서 dense page의 stroke/image 목록을 매번 복사했다.
3. 앱 background 전환에는 별도 flush가 없었고, 화면 dispose와 autosave가 같은 내용을 중복 snapshot할 수 있었다.
4. worker save 실패는 재시도 없이 폐기됐다.

## 변경

- `Document`에 저장 포맷과 무관한 runtime session/edit generation을 추가했다.
- queued/running/completed generation을 snapshot 전에 비교해 동일 내용의 목록 복사와 직렬화를 생략한다.
- 새 편집은 기존 pending snapshot을 대체하며 worker는 노트별 최신 snapshot만 순차 저장한다.
- 참고 패널도 1.2초 debounce를 사용한다.
- 메인 노트와 참고 패널 모두 `ON_STOP` 및 dispose에서 즉시 `flushSave()`를 요청한다.
- 동일 generation의 ON_STOP/dispose 중복 호출은 no-op이다.
- 일시적인 파일 I/O 실패는 worker에서 최대 3회 재시도한다.
- page revision 검사는 유지해 오래된 save 완료가 최신 편집의 dirty 상태를 지우지 못하게 했다.

## 호환성

- stroke/page/meta 파일 형식과 버전은 변경하지 않았다.
- undo/redo 경로는 변경하지 않았고 기존 `afterEdit()` 뒤에 runtime generation 증가만 추가했다.
- 저장 파일 I/O는 기존 단일 background worker와 dirty-page append/rewrite 방식을 그대로 사용한다.

## 검증

- `SaveRequestLedgerTest`: queued/running/completed 중복 제거, 새 revision/session/title 구분, 실패 후 재요청, 이전 저장 완료와 최신 pending 공존을 검증한다.
- `testDebugUnitTest`, lint, debug/release APK build를 release 전 실행한다.

## 기기 확인 항목

- 20개 이상 stroke를 연속 입력한 뒤 1.2초 이내에는 저장으로 인한 입력 끊김이 없는지 확인한다.
- 필기 직후 홈 화면으로 이동하고 앱을 복원했을 때 마지막 stroke가 남는지 확인한다.
- 필기 직후 노트를 닫고 다시 열었을 때 마지막 stroke가 남는지 확인한다.
- 참고 패널에서 연속 필기 후 즉시 패널을 닫거나 앱을 background로 보내도 내용이 남는지 확인한다.
- 빠른 undo/redo 뒤 즉시 닫고 다시 열어 최종 상태가 일치하는지 확인한다.
