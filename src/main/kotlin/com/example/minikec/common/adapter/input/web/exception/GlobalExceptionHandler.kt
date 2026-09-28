package com.example.minikec.common.adapter.input.web.exception

import com.example.minikec.user.domain.InsufficientPointException
import com.example.minikec.action.domain.ActionRepeatNotAllowedException
import com.example.minikec.action.domain.UserLockAcquisitionException
import com.example.minikec.action.domain.RewardSoldOutException
import com.example.minikec.resource.domain.ResourceNotAvailableException

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice


@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ActionRepeatNotAllowedException::class)
    fun handleRepeatNotAllowed(
        exception: ActionRepeatNotAllowedException
    ): ResponseEntity<ErrorResponse> {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "ACTION_REPEAT_NOT_ALLOWED",
                    message = exception.message
                        ?: "Action cannot be repeated"
                )
            )
    }
    
    @ExceptionHandler(InsufficientPointException::class)
    fun handleInsufficientPoint(
        exception: InsufficientPointException
    ): ResponseEntity<ErrorResponse> {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "INSUFFICIENT_POINT",
                    message = exception.message
                        ?: "Insufficient point"
                )
            )
    }
    
    @ExceptionHandler(UserLockAcquisitionException::class)
    fun handleUserLockAcquisition(
        exception: UserLockAcquisitionException
    ): ResponseEntity<ErrorResponse> {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "USER_LOCK_ACQUISITION_FAILED",
                    message = exception.message
                        ?: "Failed to acquire user lock"
                )
            )
    }
    
    @ExceptionHandler(RewardSoldOutException::class)
    fun handleRewardSoldOut(
        exception: RewardSoldOutException
    ): ResponseEntity<ErrorResponse> {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "REWARD_SOLD_OUT",
                    message = exception.message
                        ?: "Reward sold out"
                )
            )
    }

    @ExceptionHandler(ResourceNotAvailableException::class)
    fun handleResourceNotAvailable(
        exception: ResourceNotAvailableException
    ): ResponseEntity<ErrorResponse> {

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "RESOURCE_NOT_AVAILABLE",
                    message = exception.message
                    ?: "Resource not available"
                )
            )
    }
}