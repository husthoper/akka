/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import akka.actor.DeadLetterSuppression
import akka.actor.typed.BehaviorInterceptor.PreStartTarget
import akka.actor.typed.BehaviorInterceptor.ReceiveTarget
import akka.actor.typed.BehaviorInterceptor.SignalTarget
import akka.actor.typed.SupervisorStrategy._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.StashBuffer
import akka.annotation.InternalApi
import akka.event.Logging
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[akka] object Supervisor {
  def apply[T, Thr <: Throwable: ClassTag](initialBehavior: Behavior[T], strategy: SupervisorStrategy): Behavior[T] = {
    strategy match {
      case r: Restart ⇒
        Behaviors.intercept[T, T](new RestartSupervisor(initialBehavior, r))(initialBehavior)
      case r: Backoff ⇒
        Behaviors.intercept[T, T](new RestartSupervisor(initialBehavior, r))(initialBehavior)
      case r: Resume ⇒
        Behaviors.intercept[T, T](new ResumeSupervisor(r))(initialBehavior)
      case r: Stop ⇒
        Behaviors.intercept[T, T](new StopSupervisor(initialBehavior, r))(initialBehavior)
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private abstract class AbstractSupervisor[O, I, Thr <: Throwable](strategy: SupervisorStrategy)(implicit ev: ClassTag[Thr]) extends BehaviorInterceptor[O, I] {

  private val throwableClass = implicitly[ClassTag[Thr]].runtimeClass

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = {
    other match {
      case as: AbstractSupervisor[_, _, Thr] if throwableClass == as.throwableClass ⇒ true
      case _ ⇒ false
    }
  }

  override def aroundStart(ctx: ActorContext[O], target: PreStartTarget[I]): Behavior[I] = {
    try {
      target.start(ctx)
    } catch handleExceptionOnStart(ctx, target)
  }

  def aroundSignal(ctx: ActorContext[O], signal: Signal, target: SignalTarget[I]): Behavior[I] = {
    try {
      target(ctx, signal)
    } catch handleSignalException(ctx, target)
  }

  def log(ctx: ActorContext[_], t: Throwable): Unit = {
    if (strategy.loggingEnabled) {
      ctx.asScala.log.error(t, "Supervisor {} saw failure: {}", this, t.getMessage)
    }
  }

  def dropped(ctx: ActorContext[_], signalOrMessage: Any): Unit = {
    import akka.actor.typed.scaladsl.adapter._
    ctx.asScala.system.toUntyped.eventStream.publish(Dropped(signalOrMessage, ctx.asScala.self))
  }

  protected def handleExceptionOnStart(ctx: ActorContext[O], target: PreStartTarget[I]): Catcher[Behavior[I]]
  protected def handleSignalException(ctx: ActorContext[O], target: SignalTarget[I]): Catcher[Behavior[I]]
  protected def handleReceiveException(ctx: ActorContext[O], target: ReceiveTarget[I]): Catcher[Behavior[I]]

  override def toString: String = Logging.simpleName(getClass)
}

/**
 * For cases where O == I for BehaviorInterceptor.
 */
private abstract class SimpleSupervisor[T, Thr <: Throwable: ClassTag](ss: SupervisorStrategy) extends AbstractSupervisor[T, T, Thr](ss) {

  override def aroundReceive(ctx: ActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    try {
      target(ctx, msg)
    } catch handleReceiveException(ctx, target)
  }

  protected def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      Behavior.failed(t)
  }

  // convenience if target not required to handle exception
  protected def handleExceptionOnStart(ctx: ActorContext[T], target: PreStartTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleSignalException(ctx: ActorContext[T], target: SignalTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
  protected def handleReceiveException(ctx: ActorContext[T], target: ReceiveTarget[T]): Catcher[Behavior[T]] =
    handleException(ctx)
}

private class StopSupervisor[T, Thr <: Throwable: ClassTag](initial: Behavior[T], strategy: Stop) extends SimpleSupervisor[T, Thr](strategy) {
  override def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behavior.failed(t)
  }
}

private class ResumeSupervisor[T, Thr <: Throwable: ClassTag](ss: Resume) extends SimpleSupervisor[T, Thr](ss) {
  override protected def handleException(ctx: ActorContext[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      log(ctx, t)
      Behaviors.same
  }
}

private object RestartSupervisor {
  /**
   * Calculates an exponential back off delay.
   */
  def calculateDelay(
    restartCount: Int,
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double): FiniteDuration = {
    val rnd = 1.0 + ThreadLocalRandom.current().nextDouble() * randomFactor
    if (restartCount >= 30) // Duration overflow protection (> 100 years)
      maxBackoff
    else
      maxBackoff.min(minBackoff * math.pow(2, restartCount)) * rnd match {
        case f: FiniteDuration ⇒ f
        case _                 ⇒ maxBackoff
      }
  }

  case object ScheduledRestart
  final case class ResetRestartCount(current: Int) extends DeadLetterSuppression
}

private class RestartSupervisor[O, T, Thr <: Throwable: ClassTag](initial: Behavior[T], strategy: RestartOrBackoff)
  extends AbstractSupervisor[O, T, Thr](strategy) {
  import RestartSupervisor._

  private var restartingInProgress: OptionVal[(StashBuffer[Any], Set[ActorRef[Nothing]])] = OptionVal.None
  private var restartCount: Int = 0
  private var gotScheduledRestart = true
  private var deadline: OptionVal[Deadline] = OptionVal.None

  private def deadlineHasTimeLeft: Boolean = deadline match {
    case OptionVal.None    ⇒ true
    case OptionVal.Some(d) ⇒ d.hasTimeLeft
  }

  override def aroundSignal(ctx: ActorContext[O], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    restartingInProgress match {
      case OptionVal.None ⇒
        super.aroundSignal(ctx, signal, target)
      case OptionVal.Some((stashBuffer, children)) ⇒
        signal match {
          case Terminated(ref) if strategy.stopChildren && children(ref) ⇒
            val remainingChildren = children - ref
            if (remainingChildren.isEmpty && gotScheduledRestart) {
              restartCompleted(ctx)
            } else {
              restartingInProgress = OptionVal.Some((stashBuffer, remainingChildren))
              Behaviors.same
            }

          case _ ⇒
            if (stashBuffer.isFull)
              dropped(ctx, signal)
            else
              stashBuffer.stash(signal)
            Behaviors.same
        }
    }
  }

  override def aroundReceive(ctx: ActorContext[O], msg: O, target: ReceiveTarget[T]): Behavior[T] = {
    msg.asInstanceOf[Any] match {
      case ScheduledRestart ⇒
        restartingInProgress match {
          case OptionVal.Some((_, children)) ⇒
            if (strategy.stopChildren && children.nonEmpty) {
              // still waiting for children to stop
              gotScheduledRestart = true
              Behaviors.same
            } else
              restartCompleted(ctx)

          case OptionVal.None ⇒
            throw new IllegalStateException("Unexpected ScheduledRestart when restart not in progress")
        }

      case ResetRestartCount(current) ⇒
        if (current == restartCount) {
          restartCount = 0
        }
        Behavior.same

      case m: T @unchecked ⇒
        restartingInProgress match {
          case OptionVal.None ⇒
            try {
              target(ctx, m)
            } catch handleReceiveException(ctx, target)
          case OptionVal.Some((stashBuffer, _)) ⇒
            if (stashBuffer.isFull)
              dropped(ctx, m)
            else
              stashBuffer.stash(m)
            Behaviors.same
        }
    }
  }

  override protected def handleExceptionOnStart(ctx: ActorContext[O], target: PreStartTarget[T]): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      strategy match {
        case _: Restart ⇒
          // if unlimited restarts then don't restart if starting fails as it would likely be an infinite restart loop
          if (strategy.unlimitedRestarts() || ((restartCount + 1) >= strategy.maxRestarts && deadlineHasTimeLeft)) {
            // don't log here as it'll be logged as ActorInitializationException
            throw t
          } else {
            increaseRestartCount()
            prepareRestart(ctx, t)
          }
        case _: Backoff ⇒
          prepareRestart(ctx, t)
      }
  }

  override protected def handleSignalException(ctx: ActorContext[O], target: SignalTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, () ⇒ target(ctx, PreRestart))
  }
  override protected def handleReceiveException(ctx: ActorContext[O], target: ReceiveTarget[T]): Catcher[Behavior[T]] = {
    handleException(ctx, () ⇒ target.signalRestart(ctx))
  }

  private def handleException(ctx: ActorContext[O], signalRestart: () ⇒ Unit): Catcher[Behavior[T]] = {
    case NonFatal(t: Thr) ⇒
      if (strategy.maxRestarts != -1 && restartCount >= strategy.maxRestarts && deadlineHasTimeLeft) {
        strategy match {
          case _: Restart ⇒ throw t
          case _: Backoff ⇒
            log(ctx, t)
            Behavior.failed(t)
        }

      } else {
        try signalRestart() catch {
          case NonFatal(ex) ⇒ ctx.asScala.log.error(ex, "failure during PreRestart")
        }

        prepareRestart(ctx, t)
      }
  }

  private def prepareRestart(ctx: ActorContext[O], reason: Throwable): Behavior[T] = {
    log(ctx, reason)

    val currentRestartCount = restartCount
    increaseRestartCount()

    val childrenToStop = if (strategy.stopChildren) ctx.asScala.children.toSet else Set.empty[ActorRef[Nothing]]
    stopChildren(ctx, childrenToStop)

    val stashCapacity =
      if (strategy.stashCapacity >= 0) strategy.stashCapacity
      else ctx.asScala.system.settings.RestartStashCapacity
    restartingInProgress = OptionVal.Some((StashBuffer[Any](stashCapacity), childrenToStop))

    strategy match {
      case backoff: Backoff ⇒
        val restartDelay = calculateDelay(currentRestartCount, backoff.minBackoff, backoff.maxBackoff, backoff.randomFactor)
        gotScheduledRestart = false
        ctx.asScala.scheduleOnce(restartDelay, ctx.asScala.self.unsafeUpcast[Any], ScheduledRestart)
        Behaviors.empty
      case _: Restart ⇒
        if (childrenToStop.isEmpty)
          restartCompleted(ctx)
        else
          Behaviors.empty // wait for termination of children
    }
  }

  private def restartCompleted(ctx: ActorContext[O]): Behavior[T] = {
    strategy match {
      case backoff: Backoff ⇒
        gotScheduledRestart = false
        ctx.asScala.scheduleOnce(backoff.resetBackoffAfter, ctx.asScala.self.unsafeUpcast[Any], ResetRestartCount(restartCount))
      case _: Restart ⇒
    }

    try {
      val newBehavior = Behavior.validateAsInitial(Behavior.start(initial, ctx.asInstanceOf[ActorContext[T]]))
      val nextBehavior = restartingInProgress match {
        case OptionVal.None ⇒ newBehavior
        case OptionVal.Some((stashBuffer, _)) ⇒
          restartingInProgress = OptionVal.None
          stashBuffer.unstashAll(ctx.asScala.asInstanceOf[scaladsl.ActorContext[Any]], newBehavior.unsafeCast)
      }
      nextBehavior.narrow
    } catch handleException(ctx, signalRestart = () ⇒ ())
    // FIXME signal Restart is not done if unstashAll throws, unstash of each message may return a new behavior and
    //      it's the failing one that should receive the signal
  }

  private def stopChildren(ctx: ActorContext[_], children: Set[ActorRef[Nothing]]): Unit = {
    children.foreach { child ⇒
      ctx.asScala.watch(child)
      ctx.asScala.stop(child)
    }
  }

  private def increaseRestartCount(): Unit = {
    strategy match {
      case restart: Restart ⇒
        val timeLeft = deadlineHasTimeLeft
        val newDeadline = if (deadline.isDefined && timeLeft) deadline else OptionVal.Some(Deadline.now + restart.withinTimeRange)
        restartCount = if (timeLeft) restartCount + 1 else 1
        deadline = newDeadline
      case _: Backoff ⇒
        restartCount += 1
    }
  }

}

