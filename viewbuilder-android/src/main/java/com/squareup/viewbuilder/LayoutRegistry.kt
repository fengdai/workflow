/*
 * Copyright 2018 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.viewbuilder

import android.support.annotation.LayoutRes

data class LayoutBinding(
  val screenType: Class<*>,
  @LayoutRes val layoutId: Int
)

class LayoutRegistry private constructor(
  private val bindings: Map<Class<*>, Int>
) {
  constructor(vararg bindings: LayoutBinding) : this(
      mutableMapOf<Class<*>, Int>()
          .apply {
            bindings.forEach { binding -> this[binding.screenType] = binding.layoutId }
            require(keys.size == bindings.size) {
              "$bindings cannot have duplicate screen types"
            }
          }
  )

  constructor(vararg registries: LayoutRegistry) : this(
      registries
          .map { it.bindings }
          .reduce { left, right ->
            val duplicateKeys = left.keys.intersect(right.keys)
            check(
                duplicateKeys.isEmpty()
            ) { "Should not have duplicate screen types $duplicateKeys." }
            left + right
          }
  )

  @LayoutRes operator fun get(type: Class<*>): Int {
    return bindings[type] ?: throw IllegalArgumentException("Unrecognized screen type $type")
  }

  operator fun plus(binding: LayoutBinding): LayoutRegistry {
    check(binding.screenType !in bindings.keys) {
      "Already registered ${bindings[binding.screenType]} for ${binding.screenType}, " +
          "cannot accept $binding."
    }

    return LayoutRegistry(bindings + (binding.screenType to binding.layoutId))
  }

  operator fun plus(registry: LayoutRegistry): LayoutRegistry {
    return LayoutRegistry(this, registry)
  }
}

