package com.example.minikec.action.adapter.input.web

import com.example.minikec.action.application.port.input.ExecuteActionResult

data class ExecuteActionResponse(
    val actionId: String,
    val status: String,
    val currentProgress: Long,
    val goal: Long,
    val points: List<PointResponse>
) {

    companion object {

        fun from(
            result: ExecuteActionResult
        ): ExecuteActionResponse {

            return ExecuteActionResponse(
                actionId = result.actionId,
                status = result.status.name,
                currentProgress = result.currentProgress,
                goal = result.goal,
                points = result.points.map {
                    PointResponse(
                        pointKey = it.pointKey,
                        totalPoint = it.totalPoint,
                        currentPoint = it.currentPoint
                    )
                }
            )
        }
    }
}

data class PointResponse(
    val pointKey: String,
    val totalPoint: Long,
    val currentPoint: Long
)