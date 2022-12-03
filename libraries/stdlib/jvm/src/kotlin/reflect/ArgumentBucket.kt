/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

public interface ArgumentBucket {
    public operator fun get(parameter: KParameter): Any?
    public operator fun set(parameter: KParameter, arg: Any?)
    public fun isInitialized(parameter: KParameter): Boolean
}
