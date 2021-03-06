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

import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.CoroutineName
import kotlinx.coroutines.experimental.Dispatchers.Unconfined
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.EmptyCoroutineContext

/**
 * A factory for [Workflow]s implemented as state machines that:
 *
 *  - move through states of [S]
 *  - accept events of type [E]
 *  - emit a result value of type [O] when completed
 *
 * When a new workflow is [launched][launch], each consecutive state will be passed
 * to [onReact], along with a [ReceiveChannel] that can be used to accept events,
 * and a [WorkflowPool] that can be used to delegate work to nested workflows.
 *
 * [onReact] is a suspend function that eventually returns a [Reaction] indicating what to do next,
 * one of:
 *
 *   - [EnterState]: Emit [EnterState.state] from from channels previously opened by
 *   [Workflow.state][Workflow.openSubscriptionToState], and pass it as the next state value of a
 *   new call to [onReact].
 *
 *   - [FinishWith]: Deliver the result value to callers of [Workflow.await], and terminate
 *   the workflow.
 *
 * To handle events received from [Workflow.sendEvent], just receive from the channel from within
 * your [onReact] method:
 *
 *    override suspend fun onReact(
 *      state: MyState,
 *      events: ReceiveChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Reaction<MyState, MyResult> {
 *      return when(state) {
 *        FooOrDone -> when(val event = events.receive()) {
 *          Foo -> handleFoo()
 *          Done -> FinishWith(it.result)
 *          else -> throw IllegalStateException("Invalid event: $event")
 *        }
 *
 *        FooOrBarState -> when(val event = events.receive()) {
 *          Foo -> handleFoo()
 *          Bar -> EnterState(FooOrDone)
 *          else -> throw IllegalStateException("Invalid event: $event")
 *        }
 *      }
 *    }
 *
 * [onReact] is not limited to using the given [ReceiveChannel] to calculate its
 * next state. For example, a service call might be handled this way, mapping
 * a [Deferred][kotlinx.coroutines.experimental.Deferred] generated by Retrofit to the
 * appropriate [Reaction].
 *
 *    override suspend fun onReact(
 *      state: MyState,
 *      events: ReceiveChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Reaction<MyState, MyResult> {
 *      return when(state) {
 *        WaitingForStatusResponse -> statusService.update().await().let { response ->
 *          if (response.success) EnterState(ShowingStatus(response))
 *          else EnterState(ShowingFailure(response)
 *        }
 *
 *      // ...
 *    }
 *
 * If you need to mix such command-like `Deferred`s with workflow events, use
 * [select][kotlinx.coroutines.experimental.selects.select]:
 *
 *    override suspend fun onReact(
 *      state: MyState,
 *      events: ReceiveChannel<MyEvent>,
 *      workflows: WorkflowPool
 *    ): Reaction<MyState, MyResult> {
 *      return when(state) {
 *        WaitingForStatusResponse -> select {
 *
 *          statusService.update().onAwait { response ->
 *            if (response.success) EnterState(ShowingStatus(response))
 *            else EnterState(ShowingFailure(response)
 *          }
 *
 *          events.onReceive { event ->
 *            when(event) {
 *              Cancel -> EnterState(ShowingFailure(Canceled())
 *              else -> throw IllegalStateException("Invalid event: $event")
 *            }
 *          }
 *        }

 *      // ...
 *    }
 *
 * To define a state that delegates to a nested workflow, have the [S] subtype that
 * represents it implement [Delegating]. Use [WorkflowPool.awaitNextDelegateReaction]
 * when entering that state to drive the nested workflow and react to its result.
 *
 * For example, in the simplest case, where the parent workflow accepts no events
 * of its own while the delegate is running, the delegating state type would look
 * like this:
 *
 *     data class RunningNested(
 *       // Stores the state of the nested workflow, and used as its initial
 *       // state when it is started.
 *       override val delegateState: NestedState = NestedState.start()
 *     ) : MyState(), Delegating<NestedState, NestedEvent, NestedResult> {
 *       override val id = NestedReactor::class.makeWorkflowId()
 *     }
 *
 * You'd register a `NestedReactor` instance with the [WorkflowPool] passed
 * to your [launch] implementation:
 *
 *    class MyReactor(
 *      private val nestedReactor : NestedReactor
 *    ) {
 *      override fun launch(
 *        initialState: MyState,
 *        workflows: WorkflowPool
 *      ) : Workflow<MyState, MyEvent, MyResult> {
 *        workflows.register(nestedReactor)
 *        return doLaunch(initialState, workflows)
 *      }
 *
 * and in your [onReact] method, use [WorkflowPool.nextDelegateReaction]
 * to wait for the nested workflow to do its job:
 *
 *    is Delegating -> workflows.awaitNextDelegateReaction(state).let {
 *      when (it) {
 *        is EnterState -> EnterState(state.copy(delegateState = it.state))
 *        is FinishWith -> when (it.result) {
 *          is DoSomething -> EnterState(DoingSomething)
 *          is DoSomethingElse -> EnterState(DoingSomethingElse)
 *        }
 *      }
 *    }
 *
 * If you need to handle other events while the workflow is running, use the events channel and
 * `select` again.
 * Remember to call [WorkflowPool.abandonDelegate] if you leave while the nested workflow is still
 * running!
 *
 *    is Delegating -> select {
 *      workflows.nextDelegateReaction(state).onAwait {
 *        when (it) {
 *          is EnterState -> EnterState(state.copy(delegateState = it.state))
 *          if FinishWith -> when (it.result) {
 *            is DoSomething -> EnterState(DoingSomething)
 *            is DoSomethingElse -> EnterState(DoingSomethingElse)
 *          }
 *        }
 *      }
 *
 *      events.onReceive { event ->
 *        when(event) {
 *          Cancel -> {
 *            workflows.abandonDelegate(state.id)
 *            EnterState(NeverMind)
 *          }
 *          else -> throw IllegalStateException("Invalid event: $event")
 *        }
 *      }
 *    }
 *
 * To accept events for nested workflows, e.g. to drive a UI, define
 * [com.squareup.workflow.Renderer]s for both [S] and each of its [Delegating] subtypes.
 * [WorkflowPool.input] can be used by renderers to route events to any running workflow.
 *
 * @param S State type
 * @param E Event type. If your Reactor doesn't have any events, use `Nothing`
 * @param O Output (result) type
 */
interface Reactor<S : Any, E : Any, out O : Any> : WorkflowPool.Launcher<S, E, O> {
  /**
   * Called by the `Workflow` to obtain the next state transition.
   */
  suspend fun onReact(
    state: S,
    events: ReceiveChannel<E>,
    workflows: WorkflowPool
  ): Reaction<S, O>

  override fun launch(
    initialState: S,
    workflows: WorkflowPool
  ): Workflow<S, E, O> = doLaunch(initialState, workflows)
}

/**
 * Implements a [Workflow] using a [Reactor].
 * Use this to implement [WorkflowPool.Launcher.launch].
 *
 * The react loop runs inside a [workflow coroutine][workflow].
 *
 * The [initial state][initialState], and then each [subsequent state][EnterState], are all sent
 * to the builder's state channel.
 *
 * _Note:_
 * If [onReact][Reactor.onReact] immediately returns [FinishWith], the last state may not be
 * emitted since the [state channel][Workflow.openSubscriptionToState] is closed immediately.
 *
 * ## Dispatchers
 *
 * If [context] contains a
 * [CoroutineDispatcher][kotlinx.coroutines.experimental.CoroutineDispatcher], it is not used.
 * The [onReact][Reactor.onReact] method is always invoked from the [Unconfined] dispatcher. If your
 * `onReact` actually requires a particular dispatcher, it should use
 * [withContext][kotlinx.coroutines.experimental.withContext].
 */
fun <S : Any, E : Any, O : Any> Reactor<S, E, O>.doLaunch(
  initialState: S,
  workflows: WorkflowPool,
  context: CoroutineContext = EmptyCoroutineContext
): Workflow<S, E, O> {
  // Ensure the workflow has a name, for debugging.
  val coroutineName = CoroutineName(getWorkflowCoroutineName())

  // The ordering of these context pieces is significant – context elements on the right replace
  // matching elements on the left.
  //  1. The name comes before the external context, so if the external context already has a name
  //     it is used instead.
  //  2. The dispatcher comes after the external context because we want to force it:
  //     This code doesn't care what dispatcher it runs on, it isn't performing any
  //     thread-sensitive side effects. For more information about why Unconfined is used, see this
  //     module's README.
  val workflowContext = coroutineName + context + Unconfined

  return GlobalScope.workflow(context = workflowContext) { stateChannel, eventChannel ->
    var reaction: Reaction<S, O> = EnterState(initialState)
    var currentState: S?

    while (reaction is EnterState) {
      val state = reaction.state
      currentState = state
      stateChannel.send(state)

      // Wraps any errors thrown by the [Reactor.onReact] method in a [ReactorException].
      reaction = try {
        onReact(currentState, eventChannel, workflows)
      } catch (@Suppress("TooGenericExceptionCaught") cause: Throwable) {
        // CancellationExceptions aren't actually errors, rethrow them directly.
        if (cause is CancellationException) throw cause
        throw ReactorException(
            cause = cause,
            reactor = this@doLaunch,
            reactorState = currentState
        )
      }
    }
    return@workflow (reaction as FinishWith).result
  }
}

private fun Reactor<*, *, *>.getWorkflowCoroutineName() = "workflow(${this::class.qualifiedName})"
