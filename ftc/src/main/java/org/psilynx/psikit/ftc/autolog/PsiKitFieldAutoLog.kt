package org.psilynx.psikit.ftc.autolog

/**
 * Opt-in annotation for reflective field auto-logging.
 *
 * Usage:
 * - Class-level: log all fields on annotated instances (except fields marked with [PsiKitNoFieldAutoLog]).
 * - Field-level: log a specific field even if the containing class is not annotated.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PsiKitFieldAutoLog

/**
 * Opt-out annotation for reflective field auto-logging.
 *
 * Usage:
 * - Class-level: disables class-level auto-logging for that class.
 * - Field-level: excludes a specific field from auto-logging.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class PsiKitNoFieldAutoLog
