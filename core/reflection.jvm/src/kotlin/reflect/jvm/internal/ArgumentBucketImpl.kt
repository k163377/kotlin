/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect.jvm.internal

import kotlin.coroutines.Continuation
import kotlin.reflect.ArgumentBucket
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

// region utils
// from KCallableImpl, should move to util.kt?
private fun defaultEmptyArray(type: KType): Any =
    type.jvmErasure.java.run {
        if (isArray) java.lang.reflect.Array.newInstance(componentType, 0)
        else throw KotlinReflectionInternalError(
            "Cannot instantiate the default empty array of type $simpleName, because it is not an array type"
        )
    }

private fun calkMaskIndex(index: Int): Int = index / Integer.SIZE
private fun calkFlag(index: Int): Int = 1 shl (index % Integer.SIZE)
// endregion

internal class ArgumentBucketImpl(
    private val parameters: List<KParameter>,
    val arguments: Array<Any?>,
    private val masks: IntArray,
    private var argumentCount: Int
) : ArgumentBucket {
    private fun updateMask(parameter: KParameter) {
        val maskIndex = calkMaskIndex(parameter.index)
        val updatedMask = masks[maskIndex] or calkFlag(parameter.index)

        if (masks[maskIndex] != updatedMask) {
            masks[maskIndex] = updatedMask
            argumentCount++
        }
    }

    override fun get(parameter: KParameter): Any? = arguments[parameter.index]

    override fun set(parameter: KParameter, arg: Any?) {
        arguments[parameter.index] = arg
        updateMask(parameter)
    }

    fun setContinuationArgument(continuationArgument: Continuation<*>?) {
        arguments[arguments.lastIndex] = continuationArgument
    }

    override fun isInitialized(parameter: KParameter): Boolean =
        (masks[calkMaskIndex(parameter.index)] and calkFlag(parameter.index)) != 0

    // Flag whether all arguments are given (can be called with call)
    fun isFullInitialized(): Boolean = parameters.size == argumentCount

    // Generate an array of arguments to call the default function
    fun getDefaultArguments(): Array<Any?> {
        parameters.forEach {
            if (!(isInitialized(it) || it.isOptional))
                throw IllegalArgumentException("No argument provided for a required parameter: $it")
        }

        // Array containing the actual function arguments, masks, and +1 for DefaultConstructorMarker or MethodHandle.
        val defaultArguments = arrayOfNulls<Any?>(arguments.size + masks.size + 1)

        // Copy arguments and masks
        arguments.copyInto(defaultArguments)
        masks.forEachIndexed { i, mask ->
            defaultArguments[i + arguments.size] = mask
        }

        return defaultArguments
    }
}

internal class BucketGenerator(private val parameters: ReflectProperties.LazySoftVal<List<KParameter>>, isSuspend: Boolean) {
    private val absentArguments: Array<Any?>
    private val absentMasks: IntArray
    private val initialArgumentCount: Int

    init {
        val parameters = parameters()
        val parameterSize = parameters.size
        var tempArgumentCount = 0

        absentMasks = IntArray((parameterSize + Integer.SIZE - 1) / Integer.SIZE)

        absentArguments = Array(parameterSize + if (isSuspend) 1 else 0) {
            val parameter = parameters[it]

            when {
                parameter.isOptional && !parameter.type.isInlineClassType -> {
                    // For inline class types, the javaType refers to the underlying type of the inline class,
                    // but we have to pass null in order to mark the argument as absent for InlineClassAwareCaller.
                    defaultPrimitiveValue(parameter.type.javaType)
                }
                parameter.isVararg -> {
                    // In the case of vararg with no default argument,
                    // it can be called with an empty array even if no argument is given,
                    // so it is treated as initialized from the beginning.
                    val maskIndex = calkMaskIndex(parameter.index)
                    absentMasks[maskIndex] = absentMasks[maskIndex] or calkFlag(parameter.index)
                    tempArgumentCount++

                    defaultEmptyArray(parameter.type)
                }
                else -> null
            }
        }

        initialArgumentCount = tempArgumentCount
    }

    fun generate() = ArgumentBucketImpl(
        parameters(),
        absentArguments.clone(),
        absentMasks.clone(),
        initialArgumentCount
    )
}
