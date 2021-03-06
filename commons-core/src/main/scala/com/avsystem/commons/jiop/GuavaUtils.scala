package com.avsystem.commons
package jiop

import java.util.concurrent.{Executor, TimeUnit}

import com.avsystem.commons.jiop.GuavaUtils.{DecorateFutureAsGuava, DecorateFutureAsScala}
import com.avsystem.commons.misc.Sam
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.google.common.{base => gbase}

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

trait GuavaUtils {
  type GFunction[F, T] = gbase.Function[F, T]
  type GSupplier[T] = gbase.Supplier[T]
  type GPredicate[T] = gbase.Predicate[T]

  def gFunction[F, T](fun: F => T) = Sam[GFunction[F, T]](fun)
  def gSupplier[T](expr: => T) = Sam[GSupplier[T]](expr)
  def gPredicate[T](pred: T => Boolean) = Sam[GPredicate[T]](pred)

  implicit def toDecorateAsScala[T](gfut: ListenableFuture[T]): DecorateFutureAsScala[T] =
    new DecorateFutureAsScala(gfut)

  implicit def toDecorateAsGuava[T](fut: Future[T]): DecorateFutureAsGuava[T] =
    new DecorateFutureAsGuava(fut)
}

object GuavaUtils {
  class DecorateFutureAsScala[T](private val gfut: ListenableFuture[T]) extends AnyVal {
    def asScala: Future[T] = gfut match {
      case FutureAsListenableFuture(fut) => fut
      case _ => ListenableFutureAsScala(gfut)
    }

    def asScalaUnit: Future[Unit] =
      asScala.toUnit
  }

  class DecorateFutureAsGuava[T](private val fut: Future[T]) extends AnyVal {
    def asGuava: ListenableFuture[T] = fut match {
      case ListenableFutureAsScala(gfut) => gfut
      case _ => FutureAsListenableFuture(fut)
    }

    def asGuavaVoid: ListenableFuture[Void] = {
      import JavaInterop.toDecorateAsGuava
      fut.toVoid.asGuava
    }
  }

  private case class ListenableFutureAsScala[+T](gfut: ListenableFuture[T@uncheckedVariance]) extends Future[T] {
    def isCompleted: Boolean =
      gfut.isDone

    def onComplete[U](f: Try[T] => U)(implicit ec: ExecutionContext): Unit = {
      val callback = new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = f(Failure(t))
        def onSuccess(result: T): Unit = f(Success(result))
      }
      val executor = ec match {
        case executor: Executor => executor
        case _ => new Executor {
          def execute(command: Runnable): Unit =
            ec.execute(command)
        }
      }
      Futures.addCallback(gfut, callback, executor)
    }

    def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] =
      map(Success(_)).recover({ case NonFatal(t) => Failure(t) }).map(f(_).get)

    def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] =
      map(Success(_)).recover({ case NonFatal(t) => Failure(t) }).flatMap(f)

    private[this] def unwrapFailures(expr: => T): T =
      try expr catch {
        case ee: ExecutionException => throw ee.getCause
      }

    def value: Option[Try[T]] =
      if (gfut.isDone) Some(Try(unwrapFailures(gfut.get))) else None

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): T =
      if (atMost.isFinite())
        unwrapFailures(gfut.get(atMost.length, atMost.unit))
      else
        unwrapFailures(gfut.get())

    @throws(classOf[InterruptedException])
    @throws(classOf[TimeoutException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      try result(atMost) catch {
        case NonFatal(_) =>
      }
      this
    }
  }

  private case class FutureAsListenableFuture[T](fut: Future[T]) extends ListenableFuture[T] {
    def addListener(listener: Runnable, executor: Executor): Unit = {
      listener.checkNotNull("listener is null")
      val ec = executor match {
        case ec: ExecutionContext => ec
        case _ => new ExecutionContext {
          def reportFailure(cause: Throwable): Unit =
            cause.printStackTrace()
          def execute(runnable: Runnable): Unit =
            executor.execute(runnable)
        }
      }
      fut.onComplete(_ => listener.run())(ec)
    }

    def isCancelled: Boolean =
      false

    private[this] def wrapFailures(expr: => T): T =
      try expr catch {
        case NonFatal(e) => throw new ExecutionException(e)
      }

    def get(): T =
      wrapFailures(Await.result(fut, Duration.Inf))

    def get(timeout: Long, unit: TimeUnit): T =
      wrapFailures(Await.result(fut, Duration(timeout, unit)))

    def cancel(mayInterruptIfRunning: Boolean): Boolean =
      throw new UnsupportedOperationException

    def isDone: Boolean =
      fut.isCompleted
  }
}
