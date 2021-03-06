package com.avsystem.commons
package rpc

import com.avsystem.commons.concurrent.RunNowEC

import scala.concurrent.Future

/**
  * Mix in this trait into your RPC framework to support remote procedures, i.e. fire-and-forget methods
  * with `Unit` return type.
  */
trait ProcedureRPCFramework extends RPCFramework {
  type RawRPC <: ProcedureRawRPC

  trait ProcedureRawRPC { this: RawRPC =>
    def fire(rpcName: String, argLists: List[List[RawValue]]): Unit
  }

  implicit val ProcedureRealHandler: RealInvocationHandler[Unit, Unit] =
    RealInvocationHandler[Unit, Unit](_ => ())
  implicit val ProcedureRawHandler: RawInvocationHandler[Unit] =
    RawInvocationHandler[Unit](_.fire(_, _))
}

/**
  * Mix in this trait into your RPC framework to support remote functions, i.e. methods which asynchronously
  * return some result (`Future[A]` where `A` has a `Reader` and `Writer`).
  */
trait FunctionRPCFramework extends RPCFramework {
  type RawRPC <: FunctionRawRPC

  trait FunctionRawRPC { this: RawRPC =>
    def call(rpcName: String, argLists: List[List[RawValue]]): Future[RawValue]
  }

  implicit def FunctionRealHandler[A: Writer]: RealInvocationHandler[Future[A], Future[RawValue]] =
    RealInvocationHandler[Future[A], Future[RawValue]](_.mapNow(write[A] _))
  implicit def FunctionRawHandler[A: Reader]: RawInvocationHandler[Future[A]] =
    RawInvocationHandler[Future[A]]((rawRpc, rpcName, argLists) => rawRpc.call(rpcName, argLists).mapNow(read[A] _))
}

/**
  * Mix in this trait into your RPC framework to support getters, i.e. methods that return RPC subinterfaces
  */
trait GetterRPCFramework extends RPCFramework {
  type RawRPC <: GetterRawRPC

  case class RawInvocation(rpcName: String, argLists: List[List[RawValue]])

  trait GetterRawRPC { this: RawRPC =>
    def get(rpcName: String, argLists: List[List[RawValue]]): RawRPC

    def resolveGetterChain(getters: List[RawInvocation]): RawRPC =
      getters.foldRight(this)((inv, rpc) => rpc.get(inv.rpcName, inv.argLists))
  }

  // these must be macros in order to properly handle recursive RPC types
  implicit def getterRealHandler[T](implicit ev: IsRPC[T]): RealInvocationHandler[T, RawRPC] = macro macros.rpc.RPCMacros.getterRealHandler[T]
  implicit def getterRawHandler[T](implicit ev: IsRPC[T]): RawInvocationHandler[T] = macro macros.rpc.RPCMacros.getterRawHandler[T]

  final class GetterRealHandler[T: AsRawRPC] extends RealInvocationHandler[T, RawRPC] {
    def toRaw(real: T) = AsRawRPC[T].asRaw(real)
  }
  final class GetterRawHandler[T: AsRealRPC] extends RawInvocationHandler[T] {
    def toReal(rawRpc: RawRPC, rpcName: String, argLists: List[List[RawValue]]) = AsRealRPC[T].asReal(rawRpc.get(rpcName, argLists))
  }
}

trait StandardRPCFramework extends GetterRPCFramework with FunctionRPCFramework with ProcedureRPCFramework {
  trait RawRPC extends GetterRawRPC with FunctionRawRPC with ProcedureRawRPC
}

trait OneWayRPCFramework extends GetterRPCFramework with ProcedureRPCFramework {
  trait RawRPC extends GetterRawRPC with ProcedureRawRPC
}
