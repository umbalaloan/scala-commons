package com.avsystem.commons
package rpc

import scala.annotation.StaticAnnotation

/**
  * Marker annotation for all RPC interfaces. Must be applied on every trait/class representing an RPC interface.
  * </p>
  * An RPC interface is a trait or class whose abstract methods will be interpreted as remote methods by the
  * RPC framework. Remote methods must be defined according to following rules:
  * <ul>
  * <li>types of arguments must be serializable by uPickle library</li>
  * <li>return type must be either `Unit`, `Future[T]` where `T` is a type serializable by uPickle library or
  * another RPC interface</li>
  * <li>method must not have type parameters</li>
  * </ul>
  * RPC interfaces may also have non-abstract members - these will be invoked locally. However, they may invoke
  * remote members in their implementations.
  */
class RPC extends StaticAnnotation