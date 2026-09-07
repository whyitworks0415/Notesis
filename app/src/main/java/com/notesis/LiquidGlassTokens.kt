package com.notesis

import androidx.compose.ui.unit.dp

/**
 * Liquid Glass 전체에서 공유하는 기본 디자인 값입니다.
 *
 * 화면 설정에서 바꿀 수 있는 값은 [SkinSettings]가 이 기본값을 덮어쓰며,
 * 컴포넌트의 크기와 애니메이션처럼 사용자 설정과 무관한 값만 이곳에 둡니다.
 */
object LiquidGlassTokens {
    /** 유리 아래 배경을 흐리는 기본 반경입니다. */
    val blurRadius = 18.dp

    /** 가장자리에서 굴절이 일어나는 띠의 깊이입니다. */
    val refractionDepth = 12.dp

    /** 배경 픽셀이 휘어지는 기본 거리입니다. */
    val refractionAmount = 24.dp

    /** RGB 파장이 갈라지는 기본 색수차 강도(0~1)입니다. */
    const val dispersion = 0.35f

    /** 패널과 일반 컨트롤의 기본 모서리 반경입니다. */
    val cornerRadius = 22.dp

    /** API 32 이하 폴백에서 사용하는 가벼운 그림자입니다. */
    val fallbackShadow = 5.dp

    /** 슬라이더 손잡이가 눌렸을 때 커지는 비율입니다. */
    const val pressedScale = 1.15f

    /** 유리 전환 애니메이션 시간입니다. */
    const val transitionMillis = 200

    /** 세그먼트 인디케이터 spring의 감쇠비입니다. */
    const val segmentDampingRatio = 0.75f
}
