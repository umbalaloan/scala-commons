package com.avsystem.commons
package misc

import com.avsystem.commons.misc.Opt.EmptyMarker

import scala.language.implicitConversions

object Opt {
  private object EmptyMarker extends Serializable

  def apply[A](value: A): Opt[A] = new Opt[A](if (value != null) value else EmptyMarker)
  def unapply[A](opt: Opt[A]): Opt[A] = opt //name-based extractor

  def some[A](value: A): Opt[A] =
    if (value != null) new Opt[A](value)
    else throw new NullPointerException

  implicit def opt2Iterable[A](xo: Opt[A]): Iterable[A] = xo.toList

  final val Empty: Opt[Nothing] = new Opt(EmptyMarker)

  def empty[A]: Opt[A] = Empty

  private val emptyMarkerFunc: Any => Any = _ => EmptyMarker

  final class WithFilter[+A] private[Opt](self: Opt[A], p: A => Boolean) {
    def map[B](f: A => B): Opt[B] = self filter p map f
    def flatMap[B](f: A => Opt[B]): Opt[B] = self filter p flatMap f
    def foreach[U](f: A => U): Unit = self filter p foreach f
    def withFilter(q: A => Boolean): WithFilter[A] = new WithFilter[A](self, x => p(x) && q(x))
  }
}

/**
  * Like [[scala.Option Option]] but implemented as value class (avoids boxing) and treats `null` as no value.
  * Therefore, there is no equivalent for `Some(null)`.
  *
  * If you need a value-class version of [[scala.Option Option]] which differentiates between no value and `null` value,
  * use [[NOpt]].
  */
final class Opt[+A] private(private val rawValue: Any) extends AnyVal with Serializable {
  private def value: A = rawValue.asInstanceOf[A]

  @inline def isEmpty: Boolean = rawValue.asInstanceOf[AnyRef] eq EmptyMarker
  @inline def isDefined: Boolean = !isEmpty
  @inline def nonEmpty: Boolean = isDefined

  @inline def get: A =
    if (isEmpty) throw new NoSuchElementException("empty Opt") else value

  @inline def boxed[B](implicit boxing: Boxing[A, B]): Opt[B] =
    map(boxing.fun)

  @inline def unboxed[B](implicit unboxing: Unboxing[B, A]): Opt[B] =
    map(unboxing.fun)

  @inline def toOption: Option[A] =
    if (isEmpty) None else Some(value)

  @inline def toOptRef[B >: Null](implicit boxing: Boxing[A, B]): OptRef[B] =
    if (isEmpty) OptRef.Empty else OptRef(boxing.fun(value))

  @inline def getOrElse[B >: A](default: => B): B =
    if (isEmpty) default else value

  @inline def orNull[B >: A](implicit ev: Null <:< B): B =
    if (isEmpty) ev(null) else value

  @inline def map[B](f: A => B): Opt[B] =
    if (isEmpty) Opt.Empty else Opt(f(value))

  @inline def fold[B](ifEmpty: => B)(f: A => B): B =
    if (isEmpty) ifEmpty else f(value)

  @inline def flatMap[B](f: A => Opt[B]): Opt[B] =
    if (isEmpty) Opt.Empty else f(value)

  @inline def flatten[B](implicit ev: A <:< Opt[B]): Opt[B] =
    if (isEmpty) Opt.Empty else ev(value)

  @inline def filter(p: A => Boolean): Opt[A] =
    if (isEmpty || p(value)) this else Opt.Empty

  @inline def withFilter(p: A => Boolean): Opt.WithFilter[A] =
    new Opt.WithFilter[A](this, p)

  @inline def filterNot(p: A => Boolean): Opt[A] =
    if (isEmpty || !p(value)) this else Opt.Empty

  @inline def contains[A1 >: A](elem: A1): Boolean =
    !isEmpty && value == elem

  @inline def exists(p: A => Boolean): Boolean =
    !isEmpty && p(value)

  @inline def forall(p: A => Boolean): Boolean =
    isEmpty || p(value)

  @inline def foreach[U](f: A => U): Unit = {
    if (!isEmpty) f(value)
  }

  @inline def collect[B](pf: PartialFunction[A, B]): Opt[B] =
    if (!isEmpty) {
      val res = pf.applyOrElse(value, Opt.emptyMarkerFunc)
      new Opt(if (res == null) EmptyMarker else res)
    } else Opt.Empty

  @inline def orElse[B >: A](alternative: => Opt[B]): Opt[B] =
    if (isEmpty) alternative else this

  @inline def iterator: Iterator[A] =
    if (isEmpty) Iterator.empty else Iterator.single(value)

  @inline def toList: List[A] =
    if (isEmpty) List() else new ::(value, Nil)

  @inline def toRight[X](left: => X) =
    if (isEmpty) Left(left) else Right(value)

  @inline def toLeft[X](right: => X) =
    if (isEmpty) Right(right) else Left(value)

  override def toString =
    if (isEmpty) "Opt.Empty" else s"Opt($value)"
}
