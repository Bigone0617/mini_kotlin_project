package com.example.minikec.user.domain

// 상태	의미
// UNAVAILABLE	아직 수행 불가능
// PROGRESS	진행 중
// AVAILABLE	완료/보상 수령 가능한 상태
// COMPLETE	정상 완료
// WIN	추첨/미니게임 성공
// LOSE	추첨/미니게임 실패
// FAIL	실행 실패

enum class ActionStatus {

    UNAVAILABLE,

    PROGRESS,

    AVAILABLE,

    COMPLETE,

    WIN,

    LOSE,

    FAIL
}