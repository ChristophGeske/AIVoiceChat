package com.example.advancedvoice.core.util

/**
 * Minimal Either type. Useful for returning success/failure without exceptions.
 */
sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()

    inline fun <T> fold(onLeft: (L) -> T, onRight: (R) -> T): T =
        when (this) {
            is Left -> onLeft(value)
            is Right -> onRight(value)
        }
}

inline fun <L, R, T> Either<L, R>.mapRight(block: (R) -> T): Either<L, T> =
    when (this) {
        is Either.Left -> this
        is Either.Right -> Either.Right(block(this.value))
    }

inline fun <L, R, T> Either<L, R>.mapLeft(block: (L) -> T): Either<T, R> =
    when (this) {
        is Either.Left -> Either.Left(block(this.value))
        is Either.Right -> this
    }
