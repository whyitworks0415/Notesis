# 구현 언어와 담당 기능

이 문서는 앱이 직접 작성한 소스를 기준으로 합니다. Android SDK·Compose·AndroidX Ink 내부의 구현 언어는 별도입니다.

| 언어 | 현재 담당 기능 | 주요 소스 |
| --- | --- | --- |
| Kotlin | Compose 화면, Material/iOS 외관, 유리 효과 제어, 팝업·모션·색상 선택기, 첫 화면 목록과 썸네일 상태 | `MainActivity.kt`, `HomeLibrary.kt`, `Skin.kt`, `LiquidStyles.kt`, `LiquidGlassModifier.kt`, `PopupReveal.kt`, `PageSidebarParts.kt` |
| Kotlin | 펜/손 입력, 압력·예측·스트로크 수명, 지우기 트랜잭션과 실행 취소, 프레임별 작업 배분, 공간 검색·지우기의 대체 구현 | `InkCanvasView.kt`, `CompatWetInkView.kt`, `EraseTransaction.kt`, `ContinuousErase.kt`, `FrameWorkPolicy.kt` |
| Kotlin | 노트 저장·열기·백업, 페이지/PDF 로딩, 타일·미리보기 캐시, 이미지·Markdown 등 문서 기능, 설정·업데이트 | `Document.kt`, `InkTileCache.kt`, `PagePictures.kt`, `PdfSource.kt`, `AutoBackup.kt`, `AppUpdate.kt` |
| C++17 | 많은 스트로크의 사각형 경계를 BVH로 구성하고 화면/지우개 후보를 검색 | `spatial_index.cpp` |
| C++17 | 부분지우개의 경로 교차 계산, 샘플 사이까지 연속으로 자르는 구간 계산 | `erase_geometry.cpp`, `continuous_erase.cpp` |
| C++17 | Kotlin과 위 계산기를 연결하는 JNI 진입점. 직접 버퍼로 좌표·검색 결과 전달 | `spatial_jni.cpp`, Kotlin 측 `NativeBoundsTree.kt` |
| C++17 | 실험용 자체 하드웨어 버퍼와 Surface transaction을 통한 임시 잉크 제출. 기본값 **꺼짐**, 실패 시 기존 경로 사용 | `present_jni.cpp`, Kotlin 측 `PresentNative.kt` |
| Java | 직접 작성한 `.java` 구현 없음. Kotlin에서 Java/Android API를 호출하지만 별도의 Java 이관은 하지 않음 | `app/src/main/java/`는 소스 디렉터리 이름이며 그 안의 앱 소스는 `.kt` |
| Rust | 현재 도입하지 않음. Rust 소스·Cargo 프로젝트·Rust JNI 라이브러리 없음 | 해당 없음 |

## 경계

- 입력 이벤트 수집·문서 상태·UI 스레드 관리는 Kotlin에 남깁니다. 네이티브 호출이 UI·저장·실행 취소를 직접 소유하지 않습니다.
- C++는 데이터가 모여 있는 계산에 사용하며, 로딩 실패·작은 문서 등에서는 Kotlin 경로를 유지합니다.
- 이번 첫 화면 스크롤 수정은 Kotlin/Compose의 그래픽 레이어·목록 재사용·썸네일 상태 수정입니다. C++나 Rust로 UI를 다시 작성한 변경이 아닙니다.
- 네 언어를 모두 쓰는 것 자체가 성능 이득은 아닙니다. 추가 이관은 기기에서 병목을 측정한 뒤 결정해야 합니다. 현재 기기 성능 개선율을 언어별로 측정한 결과는 없습니다.
