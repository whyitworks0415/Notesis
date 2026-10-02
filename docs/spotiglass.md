# Spotiglass

Material, Glassmorphism, Liquid Glass, Spotiglass의 네 가지 UI를 선택할 수 있습니다.

Spotiglass는 [SpotiFLAC-Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/37ecd39a9ef8ac4b0d6f0cfb114e928f71e7ff09/lib/widgets/mornye_chrome.dart)의 `MornyeGlassSurface`, `MornyeGlassRim`, `MornyeTabBar`와 실제 엔진 [liquid_glass_easy 4.3.2](https://pub.dev/packages/liquid_glass_easy/versions/4.3.2)를 포팅합니다.

- 원본 GLSL 셰이더의 SDF, 연속 곡면, 굴절, 색수차와 optical rim 계산을 AGSL로 변환합니다. 생성 스크립트는 `node scripts/port-spotiglass-shader.mjs`입니다.
- 선택 렌즈는 정지하면 테두리 없는 평평한 표시입니다. 탭 이동 또는 100ms 누름 뒤 슬라이드하면 올라오며 바와 아이콘을 함께 굴절시킵니다. 선택 색상은 렌즈 아래로 연속해서 드러납니다.
- 원본의 240Hz 분할 스프링, 이동 강성 280/감쇠 31.4, 가로·세로 들림 스프링, 가속도 샘플링과 착지 후 유리를 걷어내는 동작을 Kotlin으로 옮겼습니다. 이동 중 다시 탭하면 현재 위치와 속도에서 이어집니다.
- 원본 기본값은 투명도 75%, 인터랙션 강도 100%, 바 높이 64dp, 내부 여백 6dp, 바 곡률 32dp, 렌즈 성장 9dp입니다. 인터랙션·투명도·곡률·블러·굴절량·렌즈 깊이·색수차를 설정에서 조절합니다. 이전 설정은 유지하며 ‘기본값으로’로 원본 기본값을 적용합니다.
- 일반 표면은 원본의 투명도별 Gaussian blur 4/8/12/18, 밝기별 표면 틴트, 어두운 모드의 Rec.709 명도 필터와 얇은 세로 rim gradient를 사용합니다.
- 바를 다른 유리 패널 안에 넣으면 패널의 내용 없는 표면을 별도 배경으로 기록합니다. 렌즈가 자기 자신을 읽지 않고 패널 밖의 페이지를 사각형으로 덮지 않습니다.
- Android 12L 이하, 고대비, 투명도 0%, 절전, 애니메이션 비활성화 또는 인터랙션 0%에서는 선택 표시가 평평하게 유지됩니다.

원본 파일, 버전·커밋·아카이브 해시, 양쪽 MIT 라이선스를 APK의 `assets/spotiglass/upstream`에 포함했습니다. Compose 레이아웃과 Android 그림자·블러 래스터화로 연결하며 앱의 도구와 색상은 유지합니다.

검증: JVM 이동·드래그·착지/반전 테스트, Android GPU에서 전체 AGSL 컴파일/실제 픽셀 굴절/변형 테스트, 밝은/어두운 미리보기 및 실제 도구막대의 슬라이드 선택.
