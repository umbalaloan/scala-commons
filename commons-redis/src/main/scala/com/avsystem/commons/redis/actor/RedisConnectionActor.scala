package com.avsystem.commons
package redis.actor

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import akka.actor.{Actor, ActorRef}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.avsystem.commons.jiop.JavaInterop._
import com.avsystem.commons.misc.Opt
import com.avsystem.commons.redis._
import com.avsystem.commons.redis.config.ConnectionConfig
import com.avsystem.commons.redis.exception._
import com.avsystem.commons.redis.protocol.{RedisMsg, RedisReply}
import com.avsystem.commons.redis.util.ActorLazyLogging

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

final class RedisConnectionActor(address: NodeAddress, config: ConnectionConfig)
  extends Actor with ActorLazyLogging { actor =>

  import RedisConnectionActor._
  import context._

  private object IncomingPacks {
    def unapply(msg: Any): Opt[QueuedPacks] = msg match {
      case packs: RawCommandPacks => QueuedPacks(packs, sender().opt, reserve = false).opt
      case Reserving(packs) => QueuedPacks(packs, sender().opt, reserve = true).opt
      case _ => Opt.Empty
    }
  }

  private var mustInitiallyConnect: Boolean = _
  private var initPromise: Promise[Unit] = _

  // indicates how many times connection was restarted
  private var incarnation = 0
  private var reservedBy = Opt.empty[ActorRef]
  private var reservationIncarnation = Opt.empty[Int]

  private val queuedToReserve = new JArrayDeque[QueuedPacks]
  private val queuedToWrite = new JArrayDeque[QueuedPacks]
  private var writeBuffer = ByteBuffer.allocate(config.maxWriteSizeHint.getOrElse(0) + 1024)

  def receive = {
    case IncomingPacks(packs) =>
      handlePacks(packs)
    case open: Open =>
      onOpen(open)
      become(connecting(-1))
      self ! Connect
  }

  private def onOpen(open: Open): Unit = {
    mustInitiallyConnect = open.mustInitiallyConnect
    initPromise = open.initPromise
  }

  private def handlePacks(packs: QueuedPacks): Unit = reservedBy match {
    case Opt.Empty =>
      if (packs.reserve) {
        log.debug(s"Reserving connection for ${packs.client.fold("")(_.toString)}")
        reservedBy = packs.client
      }
      queuedToWrite.addLast(packs)
    case packs.client =>
      if (reservationIncarnation.exists(_ != incarnation)) {
        // Connection was restarted during RedisOp - we must fail any subsequent requests from that RedisOp
        packs.reply(PacksResult.Failure(new ConnectionClosedException(address, Opt.Empty)))
      } else if (!queuedToWrite.isEmpty) {
        // During RedisOp, every batch must be sent separately
        packs.reply(PacksResult.Failure(new ConnectionBusyException(address)))
      } else {
        queuedToWrite.addLast(packs)
      }
    case _ =>
      queuedToReserve.addLast(packs)
  }

  private def handleRelease(): Unit = {
    log.debug(s"Releasing connection for ${reservedBy.fold("")(_.toString)}")
    reservedBy = Opt.Empty
    reservationIncarnation = Opt.Empty
    while (reservedBy.isEmpty && !queuedToReserve.isEmpty) {
      handlePacks(queuedToReserve.removeFirst())
    }
  }

  private def connecting(retry: Int): Receive = {
    case open: Open =>
      onOpen(open)
    case IncomingPacks(packs) =>
      handlePacks(packs)
    case Release if reservedBy.contains(sender()) =>
      handleRelease()
    case Connect =>
      log.debug(s"Connecting to $address")
      IO(Tcp) ! Tcp.Connect(address.socketAddress, config.localAddress.toOption, config.socketOptions, config.connectTimeout.toOption)
    case Tcp.Connected(remoteAddress, localAddress) =>
      log.debug(s"Connected to Redis at $address")
      new ConnectedTo(sender(), localAddress, remoteAddress).initialize()
    case Tcp.CommandFailed(_: Tcp.Connect) =>
      log.error(s"Connection attempt to Redis at $address failed")
      tryReconnect(retry + 1, new ConnectionFailedException(address))
    case Close(cause) =>
      close(cause, tcpConnecting = true)
    case _: Tcp.Event => //ignore, this is from previous connection
  }

  private def tryReconnect(retry: Int, failureCause: => Throwable): Unit =
    if (incarnation == 0 && mustInitiallyConnect) {
      close(failureCause)
    } else config.reconnectionStrategy.retryDelay(retry) match {
      case Opt(delay) =>
        if (delay > Duration.Zero) {
          log.info(s"Next reconnection attempt to $address in $delay")
        }
        become(connecting(retry))
        system.scheduler.scheduleOnce(delay, self, Connect)
      case Opt.Empty =>
        close(failureCause)
    }

  private final class ConnectedTo(connection: ActorRef, localAddr: InetSocketAddress, remoteAddr: InetSocketAddress)
    extends WatchState {

    private val decoder = new RedisMsg.Decoder
    private val collectors = new JArrayDeque[ReplyCollector]
    private var waitingForAck = 0
    private var open = true
    private var unwatch = false

    def become(receive: Receive) =
      context.become(receive unless {
        case Tcp.Connected(_, _) => sender() ! Tcp.Close
        case _: Tcp.Event if sender() != connection => //ignore
      })

    def initialize(): Unit = {
      connection ! Tcp.Register(self)
      val initBuffer = ByteBuffer.allocate(config.initCommands.rawCommandPacks.encodedSize)
      new ReplyCollector(config.initCommands.rawCommandPacks, initBuffer, onInitResult)
        .sendEmptyReplyOr { collector =>
          initBuffer.flip()
          val data = ByteString(initBuffer)
          logWrite(data)
          connection ! Tcp.Write(data)
          become(initializing(collector))
        }
    }

    def initializing(collector: ReplyCollector): Receive = {
      case open: Open =>
        onOpen(open)
      case IncomingPacks(packs) =>
        handlePacks(packs)
      case Release if reservedBy.contains(sender()) =>
        handleRelease()
      case Tcp.CommandFailed(_: Tcp.Write) =>
        onInitResult(PacksResult.Failure(new WriteFailedException(address)))
      case cc: Tcp.ConnectionClosed =>
        onConnectionClosed(cc)
        tryReconnect(0, new ConnectionClosedException(address, cc.getErrorCause.opt))
      case Tcp.Received(data) =>
        logReceived(data)
        try decoder.decodeMore(data)(collector.processMessage(_, this)) catch {
          case NonFatal(cause) => onInitResult(PacksResult.Failure(cause))
        }
      case Close(cause) =>
        close(cause)
    }

    def onInitResult(packsResult: PacksResult): Unit =
      try {
        config.initCommands.decodeReplies(packsResult)
        log.debug(s"Successfully initialized Redis connection $localAddr->$remoteAddr")
        initPromise.trySuccess(())
        become(ready)
        writeIfPossible()
      } catch {
        case NonFatal(cause) =>
          log.error(s"Failed to initialize Redis connection $localAddr->$remoteAddr", cause)
          close(new ConnectionInitializationFailure(cause))
      }

    def ready: Receive = {
      case open: Open =>
        onOpen(open)
        initPromise.success(())
      case IncomingPacks(packs) =>
        handlePacks(packs)
        writeIfPossible()
      case Release if reservedBy.contains(sender()) =>
        handleRelease()
        if (watching) {
          unwatch = true
        }
        writeIfPossible()
      case WriteAck =>
        waitingForAck = 0
        writeIfPossible()
      case Tcp.CommandFailed(_: Tcp.Write) =>
        onWriteFailed()
        writeIfPossible()
      case cc: Tcp.ConnectionClosed =>
        onConnectionClosed(cc)
        val cause = new ConnectionClosedException(address, cc.getErrorCause.opt)
        failAlreadySent(cause)
        tryReconnect(0, cause)
      case Tcp.Received(data) =>
        onMoreData(data)
      case Close(cause) =>
        failQueued(cause)
        // graceful close, wait for already sent commands to finish
        become(closing(cause))
    }

    def closing(cause: Throwable): Receive = {
      case open: Open =>
        onOpen(open)
        initPromise.success(())
        become(ready)
      case IncomingPacks(packs) =>
        packs.reply(PacksResult.Failure(cause))
      case Release => //ignore
      case WriteAck =>
        waitingForAck = 0
      case Tcp.CommandFailed(_: Tcp.Write) =>
        onWriteFailed()
        closeIfPossible(cause)
      case cc: Tcp.ConnectionClosed =>
        onConnectionClosed(cc)
        failAlreadySent(new ConnectionClosedException(address, cc.getErrorCause.opt))
        close(cause)
      case Tcp.Received(data) =>
        onMoreData(data)
        closeIfPossible(cause)
    }

    def onMoreData(data: ByteString): Unit = {
      logReceived(data)
      try decoder.decodeMore(data) { msg =>
        if (collectors.peekFirst.processMessage(msg, this)) {
          collectors.removeFirst()
        }
      } catch {
        case NonFatal(cause) => close(cause)
      }
    }

    def closeIfPossible(cause: Throwable): Unit =
      if (collectors.isEmpty) {
        close(cause)
      }

    def writeIfPossible(): Unit =
      if (waitingForAck == 0 && !queuedToWrite.isEmpty) {
        // Implementation of RedisOperationActor will not send more than one batch at once (before receiving response to
        // previous one). This guarantees that only the last batch in write queue can be a reserving one.
        val startingReservation = queuedToWrite.peekLast.reserve
        if (unwatch) {
          queuedToWrite.addFirst(QueuedPacks(RedisApi.Raw.BinaryTyped.unwatch, Opt.Empty, reserve = false))
          unwatch = false //TODO: what if UNWATCH fails?
        }

        var bufferSize = 0
        var packsCount = 0
        val it = queuedToWrite.iterator()
        while (it.hasNext && config.maxWriteSizeHint.forall(_ > bufferSize)) {
          packsCount += 1
          bufferSize += it.next().packs.encodedSize
        }
        waitingForAck = packsCount
        if (writeBuffer.capacity < bufferSize) {
          writeBuffer = ByteBuffer.allocate(bufferSize)
        }

        while (packsCount > 0) {
          packsCount -= 1
          val queued = queuedToWrite.removeFirst()
          new ReplyCollector(queued.packs, writeBuffer, queued.reply)
            .sendEmptyReplyOr(collectors.addLast)
        }

        if (waitingForAck > 0) {
          if (startingReservation) {
            // We're about to write first batch in a RedisOp (transaction). Save current incarnation so that in case
            // connection is restarted we can fail any subsequent batches from that RedisOp (RedisOp must be fully
            // executed on a single Redis network connection).
            reservationIncarnation = incarnation.opt
          }
          writeBuffer.flip()
          val data = ByteString(writeBuffer)
          logWrite(data)
          connection ! Tcp.Write(data, WriteAck)
          writeBuffer.clear()
        }
      }

    def close(cause: Throwable): Unit = {
      failAlreadySent(cause)
      if (open) {
        open = false
        connection ! Tcp.Close
      }
      actor.close(cause)
    }

    def failAlreadySent(cause: Throwable): Unit = {
      val failure = PacksResult.Failure(cause)
      drain(collectors)(_.callback(failure))
    }

    def onWriteFailed(): Unit = {
      log.error(s"Write command failed for Redis connection $localAddr->$remoteAddr")
      val failure = PacksResult.Failure(new WriteFailedException(address))
      while (waitingForAck > 0) {
        collectors.removeLast().callback(failure)
        waitingForAck -= 1
      }
    }

    def onConnectionClosed(cc: Tcp.ConnectionClosed): Unit = {
      open = false
      incarnation += 1
      val closeCause = cc.getErrorCause.opt
      log.error(s"Redis connection $localAddr->$remoteAddr was unexpectedly closed${closeCause.fold("")(c => s": $c")}")
    }

    def logReceived(data: ByteString): Unit = {
      log.debug(s"$localAddr <<<< $remoteAddr\n${RedisMsg.escape(data, quote = false).replaceAllLiterally("\\r\\n", "\\r\\n\n")}")
      config.debugListener.onReceive(data)
    }

    def logWrite(data: ByteString): Unit = {
      log.debug(s"$localAddr >>>> $remoteAddr\n${RedisMsg.escape(data, quote = false).replaceAllLiterally("\\n", "\\n\n")}")
      config.debugListener.onSend(data)
    }
  }

  private def drain[T](queue: JDeque[T])(fun: T => Unit): Unit =
    while (!queue.isEmpty) {
      fun(queue.removeFirst())
    }

  private def close(cause: Throwable, tcpConnecting: Boolean = false): Unit = {
    failQueued(cause)
    initPromise.tryFailure(cause)
    become(closed(cause, tcpConnecting))
  }

  private def failQueued(cause: Throwable): Unit = {
    val failure = PacksResult.Failure(cause)
    drain(queuedToWrite)(_.reply(failure))
    drain(queuedToReserve)(_.reply(failure))
  }

  private def closed(cause: Throwable, tcpConnecting: Boolean): Receive = {
    case open: Open =>
      onOpen(open)
      incarnation = 0
      become(connecting(-1))
      if (!tcpConnecting) {
        self ! Connect
      }
    case IncomingPacks(packs) =>
      packs.reply(PacksResult.Failure(cause))
    case Release => // ignore
    case Tcp.Connected(_, _) if tcpConnecting =>
      // failure may have happened while connecting, simply close the connection
      sender() ! Tcp.Close
      become(closed(cause, tcpConnecting = false))
    case _: Tcp.Event => // ignore
  }
}

object RedisConnectionActor {
  case class Open(mustInitiallyConnect: Boolean, initPromise: Promise[Unit])
  case class Close(cause: Throwable)
  case class Reserving(packs: RawCommandPacks)
  case object Release

  private object Connect
  private object WriteAck extends Tcp.Event

  private case class QueuedPacks(packs: RawCommandPacks, client: Opt[ActorRef], reserve: Boolean) {
    def reply(result: PacksResult): Unit = client.foreach(_ ! result)
  }

  class ReplyCollector(packs: RawCommandPacks, writeBuffer: ByteBuffer, val callback: PacksResult => Unit) {
    private[this] var replies: ArrayBuffer[RedisReply] = _
    private[this] var preprocessors: Any = _

    packs.emitCommandPacks { pack =>
      var commandCount = 0
      pack.rawCommands(inTransaction = false).emitCommands { rawCommand =>
        commandCount += 1
        RedisMsg.encode(rawCommand.encoded, writeBuffer)
      }
      pushPreprocessor(pack.createPreprocessor(commandCount))
    }

    def sendEmptyReplyOr(code: ReplyCollector => Unit): Unit =
      if (preprocessors == null) callback(PacksResult.Empty)
      else code(this)

    def processMessage(message: RedisMsg, state: WatchState): Boolean = {
      val packsResultOpt = preprocessors match {
        case null => Opt(PacksResult.Empty)
        case prep: ReplyPreprocessor =>
          prep.preprocess(message, state).map(PacksResult.Single)
        case queue: mutable.Queue[ReplyPreprocessor@unchecked] =>
          queue.front.preprocess(message, state).flatMap { preprocessedMsg =>
            if (replies == null) {
              replies = new ArrayBuffer(queue.length)
            }
            queue.dequeue()
            replies += preprocessedMsg
            if (queue.isEmpty) Opt(PacksResult.Multiple(replies)) else Opt.Empty
          }
      }
      packsResultOpt match {
        case Opt(result) =>
          callback(result)
          true
        case Opt.Empty =>
          false
      }
    }

    private def pushPreprocessor(preprocessor: ReplyPreprocessor): Unit =
      preprocessors match {
        case null => preprocessors = preprocessor
        case prep: ReplyPreprocessor => preprocessors = mutable.Queue(prep, preprocessor)
        case queue: mutable.Queue[ReplyPreprocessor@unchecked] => queue += preprocessor
      }
  }

  sealed trait PacksResult extends (Int => RedisReply)
  object PacksResult {
    case object Empty extends PacksResult {
      def apply(idx: Int) = throw new NoSuchElementException
    }
    case class Single(reply: RedisReply) extends PacksResult {
      def apply(idx: Int) = reply
    }
    case class Multiple(replySeq: IndexedSeq[RedisReply]) extends PacksResult {
      def apply(idx: Int) = replySeq(idx)
    }
    case class Failure(cause: Throwable) extends PacksResult {
      def apply(idx: Int) = throw cause
    }
  }

  trait DebugListener {
    def onSend(data: ByteString): Unit
    def onReceive(data: ByteString): Unit
  }
  object DevNullListener extends DebugListener {
    def onSend(data: ByteString) = ()
    def onReceive(data: ByteString) = ()
  }
}
