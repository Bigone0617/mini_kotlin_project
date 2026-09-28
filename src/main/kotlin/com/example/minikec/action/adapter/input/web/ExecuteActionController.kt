package com.example.minikec.action.adapter.input.web

import com.example.minikec.action.application.port.input.ExecuteActionCommand
import com.example.minikec.action.application.port.input.ExecuteActionUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ec/v1")
class ExecuteActionController(
    private val executeActionUseCase: ExecuteActionUseCase
) {

    @PostMapping(
        "/{gameKey}/events/{eventKey}/actions/{actionId}"
    )
    fun execute(
        @PathVariable gameKey: String,
        @PathVariable eventKey: String,
        @PathVariable actionId: String,
        @RequestBody request: ExecuteActionRequest
    ): ResponseEntity<ExecuteActionResponse> {

        val result =
            executeActionUseCase.execute(
                ExecuteActionCommand(
                    gameKey = gameKey,
                    eventKey = eventKey,
                    actionId = actionId,
                    externalUserId = request.externalUserId
                )
            )

        return ResponseEntity.ok(
            ExecuteActionResponse.from(result)
        )
    }
}