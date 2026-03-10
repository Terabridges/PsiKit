package org.psilynx.psikit.ftc

import org.psilynx.psikit.core.Logger
import org.psilynx.psikit.ftc.autolog.PsiKitFieldAutoLog
import org.psilynx.psikit.ftc.autolog.PsiKitNoFieldAutoLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

internal class FieldAutoLogger {

    private data class FieldPlan(
        val field: Field,
        val includeFieldExplicit: Boolean,
        val excludeFieldExplicit: Boolean,
        val isStatic: Boolean,
    )

    private data class ClassPlan(
        val classAutoEnabled: Boolean,
        val classAutoDisabled: Boolean,
        val fields: List<FieldPlan>,
    )

    private val classPlans = ConcurrentHashMap<Class<*>, ClassPlan>()
    private val cachedNoArgMethods = ConcurrentHashMap<String, Method?>()
    private var lastSampleNs: Long = Long.MIN_VALUE

    fun logRoot(root: Any?) {
        if (!FtcLogTuning.fieldAutoLogEnabled || root == null) return
        if (!shouldSampleNow()) return

        val rootKey = root.javaClass.simpleName.ifBlank { "Object" }
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        scanObject(
            instance = root,
            keyPath = sanitizeSegment(rootKey),
            depth = 0,
            maxDepth = FtcLogTuning.fieldAutoLogMaxDepth.coerceAtLeast(0),
            visited = visited,
        )
    }

    private fun shouldSampleNow(): Boolean {
        val period = FtcLogTuning.fieldAutoLogPeriodSec
        if (period <= 0.0) {
            lastSampleNs = System.nanoTime()
            return true
        }

        if (lastSampleNs == Long.MIN_VALUE) {
            lastSampleNs = System.nanoTime()
            return true
        }

        val elapsed = (System.nanoTime() - lastSampleNs) / 1_000_000_000.0
        if (elapsed >= period) {
            lastSampleNs = System.nanoTime()
            return true
        }

        return false
    }

    private fun scanObject(
        instance: Any,
        keyPath: String,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<Any>,
    ) {
        if (!visited.add(instance)) return

        val plan = classPlans.computeIfAbsent(instance.javaClass) { buildClassPlan(it) }
        val classEnabled = plan.classAutoEnabled && !plan.classAutoDisabled

        for (fieldPlan in plan.fields) {
            if (fieldPlan.excludeFieldExplicit) continue
            if (!FtcLogTuning.fieldAutoLogIncludeStaticFields && fieldPlan.isStatic) continue

            val shouldLogField = classEnabled || fieldPlan.includeFieldExplicit
            val value = try {
                fieldPlan.field.get(instance)
            } catch (_: Throwable) {
                null
            }

            if (isSkippedNoiseType(value)) continue

            if (shouldLogField) {
                val fieldSegment = sanitizeSegment(fieldPlan.field.name)
                val declaringClass = fieldPlan.field.declaringClass.simpleName.ifBlank { "UnknownClass" }
                val declaringClassSegment = sanitizeSegment(declaringClass)
                logValue("$declaringClassSegment/$fieldSegment", value)
            }

            if (depth >= maxDepth) continue
            if (!shouldTraverseValue(value)) continue

            scanObject(
                instance = value!!,
                keyPath = "$keyPath/${sanitizeSegment(fieldPlan.field.name)}",
                depth = depth + 1,
                maxDepth = maxDepth,
                visited = visited,
            )
        }
    }

    private fun buildClassPlan(clazz: Class<*>): ClassPlan {
        val fields = mutableListOf<FieldPlan>()

        for (field in clazz.declaredFields) {
            if (field.isSynthetic) continue
            try {
                field.isAccessible = true
            } catch (_: Throwable) {
                // ignore
            }
            fields += FieldPlan(
                field = field,
                includeFieldExplicit = field.isAnnotationPresent(PsiKitFieldAutoLog::class.java),
                excludeFieldExplicit = field.isAnnotationPresent(PsiKitNoFieldAutoLog::class.java),
                isStatic = Modifier.isStatic(field.modifiers),
            )
        }

        return ClassPlan(
            classAutoEnabled = clazz.isAnnotationPresent(PsiKitFieldAutoLog::class.java),
            classAutoDisabled = clazz.isAnnotationPresent(PsiKitNoFieldAutoLog::class.java),
            fields = fields,
        )
    }

    private fun shouldTraverseValue(value: Any?): Boolean {
        if (value == null) return false
        val clazz = value.javaClass
        if (isSkippedNoiseType(value)) return false

        if (clazz.isPrimitive) return false
        if (clazz.isArray) return false
        if (clazz.isEnum) return false
        if (Number::class.java.isAssignableFrom(clazz)) return false
        if (CharSequence::class.java.isAssignableFrom(clazz)) return false
        if (clazz == java.lang.Boolean::class.java || clazz == java.lang.Character::class.java) return false

        return true
    }

    private fun logValue(keyPath: String, value: Any?) {
        val base = FtcLogTuning.fieldAutoLogKeyPrefix.trimEnd('/').ifBlank { "PsiKit/Fields" }
        val key = "$base/$keyPath"

        if (value != null && logCustomValue(key, value)) {
            return
        }

        when (value) {
            null -> Logger.recordOutput("$key/IsNull", 1.0)
            is Boolean -> Logger.recordOutput(key, if (value) 1.0 else 0.0)
            is Byte -> Logger.recordOutput(key, value.toDouble())
            is Short -> Logger.recordOutput(key, value.toDouble())
            is Int -> Logger.recordOutput(key, value.toDouble())
            is Long -> Logger.recordOutput(key, value.toDouble())
            is Float -> Logger.recordOutput(key, value.toDouble())
            is Double -> Logger.recordOutput(key, value)
            is Char -> Logger.recordOutput(key, value.code.toDouble())
            is String -> Logger.recordOutput(key, value)
            is Enum<*> -> Logger.recordOutput(key, value.name)
            else -> {
                // Skip default object toString() logging for complex types;
                // class-name outputs are usually noisy and low-value.
            }
        }
    }

    private fun logCustomValue(key: String, value: Any): Boolean {
        return when (value.javaClass.name) {
            "com.sfdev.assembly.state.StateMachine" -> {
                val state = invokeNoArg(value, "getState")
                when (state) {
                    null -> Logger.recordOutput("$key/State/IsNull", 1.0)
                    is Enum<*> -> Logger.recordOutput("$key/State", state.name)
                    is String -> Logger.recordOutput("$key/State", state)
                    is Number -> Logger.recordOutput("$key/State", state.toDouble())
                    is Boolean -> Logger.recordOutput("$key/State", if (state) 1.0 else 0.0)
                    else -> Logger.recordOutput("$key/State", state.toString())
                }
                true
            }

            else -> false
        }
    }

    private fun invokeNoArg(instance: Any, methodName: String): Any? {
        val key = "${instance.javaClass.name}#$methodName"
        val method = cachedNoArgMethods.computeIfAbsent(key) {
            try {
                val found = instance.javaClass.getMethod(methodName)
                found.isAccessible = true
                found
            } catch (_: Throwable) {
                null
            }
        } ?: return null

        return try {
            method.invoke(instance)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSkippedNoiseType(value: Any?): Boolean {
        if (value == null) return false
        return when (value.javaClass.name) {
            "com.qualcomm.robotcore.hardware.Gamepad" -> true
            "org.firstinspires.ftc.teamcode.config.utility.EdgeDetector" -> true
            else -> false
        }
    }

    private fun sanitizeSegment(raw: String): String {
        return raw.replace('/', '_').replace(' ', '_')
    }
}
