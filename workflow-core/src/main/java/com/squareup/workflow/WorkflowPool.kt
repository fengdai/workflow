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
import com.squareup.workflow.WorkflowPool.Launcher
import com.squareup.workflow.WorkflowPool.Type
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers.Unconfined
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.consume
import org.jetbrains.annotations.TestOnly
import kotlin.reflect.KClass

/**
 * Runs [named][Id] [Workflow] instances, typically those associated with [WorkflowHandle]
 * states of composite workflows.
 */
class WorkflowPool {
  /**
   * Represents the type parameters of a [Launcher] in reified form.
   *
   * To get the type of a [Launcher], use one of the following properties:
   *  - [Launcher.workflowType]
   *  - `KClass<Launcher>.workflowType`
   */
  class Type<S : Any, in E : Any, out O : Any>(
    stateType: KClass<S>,
    eventType: KClass<E>,
    outputType: KClass<O>
  ) {
    private val types = arrayOf(stateType, eventType, outputType)

    /** Only calculate this once. */
    private val hashCode = types.contentHashCode()

    /**
     * Creates an id for an instance of this type to be managed by a [WorkflowPool].
     *
     * @param name allows multiple workflows of the same type to be managed at once. If
     * no name is specified, we unique only on the [Type] itself.
     */
    fun makeWorkflowId(name: String = ""): Id<S, E, O> = Id(name, this)

    override fun hashCode(): Int = hashCode
    override fun equals(other: Any?): Boolean = when {
      other === this -> true
      other is Type<*, *, *> -> types.contentEquals(other.types)
      else -> false
    }
  }

  /**
   * When [registered][WorkflowPool.register] with a [WorkflowPool],
   * creates [Workflow] instances as needed.
   *
   * Launchers are registered by and matched to [Id]s by the
   * [tuple of all their type parameters][Type]. This means that only one launcher can be registered
   * for a given combination of [S], [E], and [O] (subsequent launchers will replace
   * previously-registered ones).
   *
   * Instances may be created on demand as a side effect of calls to
   * [WorkflowPool.workflowUpdate]. They are reaped as they are
   * [completed][Workflow.invokeOnCompletion], including via calls to
   * [WorkflowPool.abandonWorkflow].
   */
  interface Launcher<S : Any, in E : Any, out O : Any> {
    fun launch(
      initialState: S,
      workflows: WorkflowPool
    ): Workflow<S, E, O>
  }

  /**
   * Unique identifier for a particular [Workflow] to be run by a [WorkflowPool].
   * See [Type.makeWorkflowId] for details.
   *
   * A convenience extension functions exists on `KClass<Launcher>` to create IDs:
   *  - `KClass<Launcher>.makeWorkflowId()`
   */
  data class Id<S : Any, in E : Any, out O : Any>
  internal constructor(
    val name: String,
    val workflowType: Type<S, E, O>
  )

  private class WorkflowEntry(val workflow: Workflow<*, *, *>)

  private val launchers = mutableMapOf<Type<*, *, *>, Launcher<*, *, *>>()
  private val workflows = mutableMapOf<Id<*, *, *>, WorkflowEntry>()

  @get:TestOnly val peekWorkflowsCount get() = workflows.values.size

  /**
   * Registers the [Launcher] to be used to create workflows that match its [Launcher.workflowType],
   * in response to calls to [workflowUpdate]. A previously registered
   * matching [Launcher] will be replaced, with the intention of allowing redundant calls
   * to be safe.
   */
  fun <S : Any, E : Any, O : Any> register(
    launcher: Launcher<S, E, O>,
    type: Type<S, E, O>
  ) {
    launchers[type] = launcher
  }

  /**
   * Starts the required nested workflow if it wasn't already running. Suspends until the next time
   * the nested workflow updates its state, or completes, and then returns that [Reaction].
   * States that are equal to the [RunningWorkflow.state] are skipped.
   *
   * If the nested workflow was not already running, it is started in the
   * [given state][RunningWorkflow.state]. Note that the initial state is
   * not returned, since states matching the given state are skipped.
   *
   * @throws kotlinx.coroutines.experimental.CancellationException If the nested workflow is
   * [abandoned][abandonWorkflow].
   * @see workflowUpdate
   */
  suspend fun <S : Any, E : Any, O : Any> awaitWorkflowUpdate(
    handle: RunningWorkflow<S, E, O>
  ): WorkflowHandle<S, E, O> {
    val workflow = requireWorkflow(handle)
    workflow.openSubscriptionToState()
        .consume {
          removeCompletedWorkflowAfter(handle.id) {
            var state = receiveOrNull()
            // Skip all the states that match the handle's state.
            while (state == handle.state) {
              state = receiveOrNull()
            }
            return state
                ?.let { handle.copy(state = it) }
                ?: FinishedWorkflow(handle.id, workflow.await())
          }
        }
  }

  /**
   * Starts the required [Worker] if it wasn't already running. Suspends until the worker
   * completes and then returns its result.
   *
   * If the worker was not already running, it is started with the given input.
   *
   * @throws kotlinx.coroutines.experimental.CancellationException If the worker is
   * [abandoned][abandonWorker].
   * @see workerResult
   */
  suspend inline fun <reified I : Any, reified O : Any> awaitWorkerResult(
    worker: Worker<I, O>,
    input: I,
    name: String = ""
  ): O = awaitWorkerResult(worker, input, name, worker.workflowType)

  /**
   * Hides the actual logic of starting processes from being inline in external code.
   */
  @PublishedApi
  internal suspend fun <I : Any, O : Any> awaitWorkerResult(
    worker: Worker<I, O>,
    input: I,
    name: String,
    type: Type<I, Nothing, O>
  ): O {
    register(worker.asLauncher(), type)
    val handle = RunningWorkflow(type.makeWorkflowId(name), input)
    val workflow = requireWorkflow(handle)

    removeCompletedWorkflowAfter(handle.id) {
      return workflow.await()
    }
  }

  /**
   * Returns a [WorkflowInput] that will route events to the identified [Workflow],
   * if it is running. That check is made each time an event is sent: if the workflow
   * is running at the moment, the event is delivered. If not, it is dropped.
   *
   * This method _does not_ start a new workflow if none was running already.
   */
  fun <E : Any> input(id: Id<*, E, *>): WorkflowInput<E> = object : WorkflowInput<E> {
    override fun sendEvent(event: E) {
      workflows[id]?.let {
        @Suppress("UNCHECKED_CAST")
        (it.workflow as WorkflowInput<E>).sendEvent(event)
      }
    }
  }

  fun <E : Any> input(handle: RunningWorkflow<*, E, *>): WorkflowInput<E> = input(handle.id)

    /**
   * Abandons the identified workflow if it was already running. If it wasn't, this is a no-op.
   *
   * To abandon a [Worker], use [abandonWorker].
   *
   * @see abandonAll
   */
  fun abandonWorkflow(id: Id<*, *, *>) {
    workflows[id]?.workflow?.cancel()
  }

  fun abandonWorkflow(handle: RunningWorkflow<*, *, *>) = abandonWorkflow(handle.id)

  /**
   * Abandons the identified [Worker] if it was already running. If it wasn't, this is a no-op.
   *
   * To abandon a nested worker, use [abandonWorker].
   *
   * @see abandonAll
   */
  inline fun <reified I : Any, reified O : Any> abandonWorker(
    worker: Worker<I, O>,
    name: String = ""
  ) = abandonWorkflow(worker.makeWorkflowId(name))

  /**
   * Abandons all workflows currently running in this pool.
   *
   * @see abandonWorkflow
   * @see abandonWorker
   */
  fun abandonAll() {
    workflows.values.forEach { it.workflow.cancel() }
  }

  private fun <S : Any, E : Any, O : Any> launcher(
    type: Type<S, E, O>
  ): Launcher<S, E, O> {
    val launcher = launchers[type]
    check(launcher != null) {
      "Expected launcher for \"$type\". Did you forget to call WorkflowPool.register()?"
    }
    @Suppress("UNCHECKED_CAST")
    return launcher as Launcher<S, E, O>
  }

  private fun <S : Any, E : Any, O : Any> requireWorkflow(
    handle: RunningWorkflow<S, E, O>
  ): Workflow<S, E, O> {
    // Some complexity here to handle workflows that complete the moment
    // they are started. We want to return the short-lived workflow so that its
    // result can be processed, but we also need to make sure it doesn't linger
    // in the map.

    @Suppress("UNCHECKED_CAST")
    var workflow = workflows[handle.id]?.workflow as Workflow<S, E, O>?

    if (workflow == null) {
      workflow = launcher(handle.id.workflowType).launch(handle.state, this)

      val entry = WorkflowEntry(workflow)
      // This entry will eventually be removed from the map by removeCompletedWorkflowAfter.
      workflows[handle.id] = entry
    }
    return workflow
  }

  /**
   * Ensures that the workflow identified by [id] is removed from the pool map before returning,
   * iff that workflow is completed after [block] returns or throws.
   *
   * **This function must be used any time a workflow result is about to be reported.**
   */
  private inline fun <R : Any> removeCompletedWorkflowAfter(
    id: Id<*, *, *>,
    block: () -> R
  ): R = try {
    block()
  } finally {
    workflows[id]?.let { workflow ->
      if (workflow.workflow.isCompleted) {
        workflows -= id
      }
    }
  }
}

/**
 * Registers the [Launcher] to be used to create workflows that match its [Launcher.workflowType],
 * in response to calls to [workflowUpdate]. A previously registered
 * matching [Launcher] will be replaced, with the intention of allowing redundant calls
 * to be safe.
 */
// Note: This is defined as an extension function so that custom register functions can be defined
// that can implement custom behavior for specific launcher sub-types.
inline fun <reified S : Any, reified E : Any, reified O : Any> WorkflowPool.register(
  launcher: Launcher<S, E, O>
) = register(launcher, launcher.workflowType)

/**
 * This is a convenience method that wraps
 * [WorkflowPool.awaitWorkflowUpdate] in a [Deferred] so it can
 * be selected on.
 *
 * @see WorkflowPool.awaitWorkflowUpdate
 */
fun <S : Any, E : Any, O : Any> WorkflowPool.workflowUpdate(
  handle: RunningWorkflow<S, E, O>
): Deferred<WorkflowHandle<S, E, O>> = GlobalScope.async(Unconfined) {
  awaitWorkflowUpdate(handle)
}

/**
 * This is a convenience method that wraps [awaitWorkerResult][WorkflowPool.awaitWorkerResult]
 * in a [Deferred] so it can be selected on.
 *
 * @see WorkflowPool.awaitWorkerResult
 */
inline fun <reified I : Any, reified O : Any> WorkflowPool.workerResult(
  worker: Worker<I, O>,
  input: I,
  name: String = ""
): Deferred<O> = workerResult(worker, input, name, worker.workflowType)

/**
 * Hides the implementation of [workerResult] above from being inlined in public code.
 */
@PublishedApi
internal fun <I : Any, O : Any> WorkflowPool.workerResult(
  worker: Worker<I, O>,
  input: I,
  name: String = "",
  type: Type<I, Nothing, O>
) = GlobalScope.async(Unconfined) { awaitWorkerResult(worker, input, name, type) }

/**
 * Returns the [Type] that represents this [Launcher]'s type parameters.
 */
@Suppress("unused")
inline val <reified S : Any, reified E : Any, reified O : Any>
    Launcher<S, E, O>.workflowType: Type<S, E, O>
  get() = Type(S::class, E::class, O::class)

/**
 * Make an ID for the [workflowType] of this [WorkflowHandle].
 *
 * E.g. `MyLauncher::class.makeWorkflowId()`
 *
 * @see Type.makeWorkflowId
 */
@Suppress("unused")
inline fun <reified S : Any, reified E : Any, reified O : Any>
    KClass<out Launcher<S, E, O>>.makeWorkflowId(name: String = ""): Id<S, E, O> =
  workflowType.makeWorkflowId(name)

/**
 * Returns the [Type] that represents this [Launcher] class's type parameters.
 *
 * E.g. `MyLauncher::class.workflowType`
 */
@Suppress("unused")
inline val <reified S : Any, reified E : Any, reified O : Any>
    KClass<out Launcher<S, E, O>>.workflowType: Type<S, E, O>
  get() = Type(S::class, E::class, O::class)

private fun <I : Any, O : Any> Worker<I, O>.asLauncher() = object : Launcher<I, Nothing, O> {
  override fun launch(
    initialState: I,
    workflows: WorkflowPool
  ): Workflow<@UnsafeVariance I, Nothing, O> = GlobalScope.workflow(Unconfined) { _, _ ->
    call(initialState)
  }
}
