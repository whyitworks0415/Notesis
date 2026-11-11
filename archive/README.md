# archive

보관해 둔 코드입니다. **빌드에 들어가지 않습니다**(`app/src` 밖). 소유자가 다시 적용해 달라고 할 때만 꺼냅니다 (`Todo.md` 7번).

| 폴더 | 내용 |
|---|---|
| `glass-skins/` | 유리 스킨 두 가지(Glassmorphism, Liquid glass)와 Liquid glass의 스타일(Notesis, iOS, Mornye), 그 설정 화면 |
| `popup-pour/` | 유리 스킨에서 메뉴·패널·대화상자가 버튼에서 "쏟아지며" 열리던 애니메이션 |

두 폴더 모두 **0.90.28 (228)** 시점의 원본입니다. 그 버전의 전체 소스는 git 태그 `v0.90.28-228`
(커밋 `16172ed`)에 그대로 있으므로, 여기 없는 파일이나 호출하던 쪽 코드는 거기서 볼 수 있습니다.

```
git show v0.90.28-228:app/src/main/java/com/notesis/MainActivity.kt
git diff v0.90.28-228 HEAD -- app/src/main/java/com/notesis/Skin.kt
```

이 폴더의 `.kt` 파일은 참고용 사본이라 지금 코드와 맞지 않을 수 있습니다. 검색할 때는 `archive/`를 빼고 찾으세요.
