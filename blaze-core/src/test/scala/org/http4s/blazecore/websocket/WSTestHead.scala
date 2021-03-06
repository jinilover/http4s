package org.http4s.blazecore.websocket

import cats.effect.{IO, Timer}
//import java.util.concurrent.ConcurrentLinkedQueue
import org.http4s.blaze.pipeline.HeadStage
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import scala.concurrent.Future
import scala.concurrent.duration._

/** A simple stage to help test websocket requests
  *
  * This is really disgusting code but bear with me here:
  * `java.util.LinkedBlockingDeque` does NOT have nodes with
  * atomic references. We need to check finalizers, and those are run concurrently
  * and nondeterministically, so we're in a sort of hairy situation
  * for checking finalizers doing anything other than waiting on an update
  *
  * That is, on updates, we may easily run into a lost update problem if
  * nodes are checked by a different thread since node values have no
  * atomicity guarantee by the jvm. I simply want to provide a (blocking)
  * way of reading a websocket frame, to emulate reading from a socket.
  *
  */
sealed abstract class WSTestHead(
    inQueue: fs2.async.mutable.Queue[IO, WebSocketFrame],
    outQueue: fs2.async.mutable.Queue[IO, WebSocketFrame])(implicit T: Timer[IO])
    extends HeadStage[WebSocketFrame] {

  /** Block while we put elements into our queue
    *
    * @return
    */
  override def readRequest(size: Int): Future[WebSocketFrame] =
    inQueue.dequeue1.unsafeToFuture()

  /** Sent downstream from the websocket stage,
    * put the result in our outqueue, so we may
    * pull from it later to inspect it
    */
  override def writeRequest(data: WebSocketFrame): Future[Unit] =
    outQueue.enqueue1(data).unsafeToFuture()

  /** Insert data into the read queue,
    * so it's read by the websocket stage
    * @param ws
    */
  def put(ws: WebSocketFrame): Unit = {
    inQueue.enqueue1(ws).unsafeRunSync(); ()
  }

  /** poll our queue for a value,
    * timing out after `timeoutSeconds` seconds
    * runWorker(this);
    */
  def poll(timeoutSeconds: Long): Option[WebSocketFrame] =
    IO.race(Timer[IO].sleep(timeoutSeconds.seconds), outQueue.dequeue1)
      .map {
        case Left(_) => None
        case Right(wsFrame) =>
          Some(wsFrame)
      }
      .unsafeRunSync()

  def pollBatch(batchSize: Int, timeoutSeconds: Long): List[WebSocketFrame] =
    IO.race(Timer[IO].sleep(timeoutSeconds.seconds), outQueue.dequeueBatch1(batchSize))
      .map {
        case Left(_) => Nil
        case Right(wsFrame) => wsFrame.toList
      }
      .unsafeRunSync()

  override def name: String = "WS test stage"
}

object WSTestHead {
  def apply()(implicit t: Timer[IO]): WSTestHead = {
    val inQueue =
      fs2.async.mutable.Queue.unbounded[IO, WebSocketFrame].unsafeRunSync()
    val outQueue =
      fs2.async.mutable.Queue.unbounded[IO, WebSocketFrame].unsafeRunSync()
    new WSTestHead(inQueue, outQueue) {}
  }
}
