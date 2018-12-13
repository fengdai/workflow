/*
 * Copyright 2017 Square Inc.
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
package com.squareup.workflow

import com.squareup.workflow.WorkflowPool.Id
import com.squareup.workflow.WorkflowPool.Type

/**
 * Reference to a workflow to be run by a [WorkflowPool], reflecting its
 * last known [state][RunningWorkflow.state] or [result][FinishedWorkflow.result].
 */
sealed class WorkflowHandle<S : Any, E : Any, O : Any> {
  /**
   * Uniquely identifies the delegate across the [WorkflowPool].
   * See [WorkflowPool.Type.makeWorkflowId] for details.
   */
  abstract val id: Id<S, E, O>
}

data class RunningWorkflow<S : Any, E : Any, O : Any>(
  override val id: Id<S, E, O>,
  val state: S
) : WorkflowHandle<S, E, O>()

data class FinishedWorkflow<S : Any, E : Any, O : Any>(
  override val id: Id<S, E, O>,
  val result: O
) : WorkflowHandle<S, E, O>()

/**
 * Make an ID for the [workflowType] of this [WorkflowHandle].
 *
 * @see Type.makeWorkflowId
 */
@Suppress("unused")
inline fun <reified S : Any, reified E : Any, reified O : Any>
    WorkflowHandle<S, E, O>.makeWorkflowId(name: String = ""): Id<S, E, O> =
// We can't use id.type since ID hasn't been initialized yet.
  Type(S::class, E::class, O::class).makeWorkflowId(name)
