package com.avsystem.commons
package redis

import java.net.InetSocketAddress

object NodeAddress {
  final val Default = NodeAddress()
  final val DefaultIP = "127.0.0.1"
  final val DefaultPort = 6379

  def parse(str: String): NodeAddress = {
    val Array(ip, port) = str.split(':')
    NodeAddress(ip, port.toInt)
  }
}
final case class NodeAddress(ip: String = NodeAddress.DefaultIP, port: Int = NodeAddress.DefaultPort) {
  def socketAddress = new InetSocketAddress(ip, port)
  override def toString = s"$ip:$port"
}
