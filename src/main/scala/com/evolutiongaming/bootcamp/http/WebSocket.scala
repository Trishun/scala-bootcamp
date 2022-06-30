package com.evolutiongaming.bootcamp.http

import cats.effect.{Clock, ExitCode, IO, IOApp, Resource}
import cats.syntax.all._
import fs2.{Pipe, Pull, Stream}
import fs2.concurrent.{Queue, Topic}
import org.http4s._
import org.http4s.client.jdkhttpclient.{JdkWSClient, WSFrame, WSRequest}
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

import java.net.http.HttpClient
import java.time.{Instant, Duration => JavaDuration}
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object WebSocketIntroduction {

  // WEBSOCKET

  // One of the main limitations of HTTP is its request-response model. The server can only send data to the
  // client, when the client requests it to. Unlike HTTP, WebSocket provides full-duplex communication. That
  // means the client and the server can send data to each other in both directions at the same time.

  // WebSocket is distinct from HTTP. However, both protocols use TCP (Transmission Control Protocol) as their
  // transport. In addition, WebSocket utilizes the same ports as HTTP by default (443 and 80) and uses HTTP
  // `Upgrade` header during its handshake. It means that, to establish a WebSocket connection, the client and
  // the server establish an HTTP connection first. Then the client proposes an upgrade to WebSocket. If the
  // server accepts, a new WebSocket connection is established.

  // WebSocket communication consists of frames (data fragments), which can be sent both by the client and
  // the server. Frames can be of several types:
  // * text frames contain text data;
  // * binary frames contain binary data;
  // * ping/pong frames check the connection (when sent from the server, the client responds automatically);
  // * other service frames: connection close frame, etc.

  // Developers usually directly work with text and binary frames only. In contrary to HTTP, WebSocket does
  // not enforce any specific message format, so frames can contain any text or binary data.
}

object WebSocketServer extends IOApp {

  // Let's build a WebSocket server using Http4s.

  private val echoRoute = HttpRoutes.of[IO] {

    // websocat "ws://localhost:9002/echo"
    case GET -> Root / "echo" =>
      // Pipe is a stream transformation function of type `Stream[F, I] => Stream[F, O]`. In this case
      // `I == O == WebSocketFrame`. So the pipe transforms incoming WebSocket messages from the client to
      // outgoing WebSocket messages to send to the client.
      def echoPipe(joinedAt: Instant): Pipe[IO, WebSocketFrame, WebSocketFrame] =
        _.collect { // filter + map
          case WebSocketFrame.Text(message, _) => message.trim
        }
          .evalMap {
//            case "time" => IO.delay(Instant.now().toString)
//            case "time" => timer.clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli(_).toString)
            case "time" => timer.clock.instantNow.map(_.toString) // Clock[F].instantNow => F[Instant]
            case text   => IO.pure(text)
          }
          .merge(Stream.eval(reportDuration(joinedAt)).delayBy(5.seconds).repeat)
          .map(WebSocketFrame.Text(_))

      def reportDuration(start: Instant): IO[String] =
        timer.clock.instantNow.map { now =>
          val duration = JavaDuration.between(start, now)
          s"You have been connected for ${duration.toSeconds} seconds!"
        }

//      val echoPipe: Pipe[IO, WebSocketFrame, WebSocketFrame] =
//        _.evalMap {
//          case WebSocketFrame.Text(message, _) => message.trim match {
//            case "time" => for {
//              //              time <- IO.delay(Instant.now())
//              time <- timer.clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli(_).toString)
//            } yield WebSocketFrame.Text(time)
//            case text => IO(WebSocketFrame.Text(text))
//          }
//
//          case _ => IO(WebSocketFrame.Text("It's not a WebSocketFrame.Text - I don't know what to do."))
//        }

      for {
        // Unbounded queue to store WebSocket messages from the client, which are pending to be processed.
        // For production use bounded queue seems a better choice. Unbounded queue may result in out of
        // memory error, if the client is sending messages quicker than the server can process them.
        joinedAt <- timer.clock.instantNow
        queue    <- Queue.unbounded[IO, WebSocketFrame]
        response <- WebSocketBuilder[IO].build(
                      // Sink, where the incoming WebSocket messages from the client are pushed to.
                      receive = queue.enqueue,
                      // Outgoing stream of WebSocket messages to send to the client.
                      send = queue.dequeue.through(echoPipe(joinedAt))
                    )
      } yield response

    // Exercise 1. Change the echo route to respond with the current time, when the client sends "time". Allow
    // whitespace characters before and after the command, so " time " should also be considered valid. Note
    // that getting the current time is a side effect.

    // Exercise 2. Change the echo route to notify the client every 5 seconds how long it is connected.
    // Tip: you can merge streams via `merge` operator.
  }

  // Topics provide an implementation of the publish-subscribe pattern with an arbitrary number of
  // publishers and an arbitrary number of subscribers.
  private def chatRoute(chatTopic: Topic[IO, String]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      // websocat "ws://localhost:9002/chat"
      case GET -> Root / "chat" =>
        WebSocketBuilder[IO].build(
          // Sink, where the incoming WebSocket messages from the client are pushed to.
          receive = chatTopic.publish.compose[Stream[IO, WebSocketFrame]](
            _
              .collect { case WebSocketFrame.Text(message, _) => message }
              .pull
              .uncons1
              .flatMap {
                case None                 => Pull.done
                case Some((name, stream)) => stream.map(message => s"$name: $message").pull.echo
              }
              .stream
          ),
          // Outgoing stream of WebSocket messages to send to the client.
          send = chatTopic.subscribe(maxQueued = 10).map(WebSocketFrame.Text(_))
        )

      // Exercise 3. Change the chat route to use the first message from a client as its username and prepend
      // it to every follow-up message. Tip: you will likely need to use fs2.Pull.
    }

  private def httpApp(chatTopic: Topic[IO, String]): HttpApp[IO] = {
    echoRoute <+> chatRoute(chatTopic)
  }.orNotFound

  override def run(args: List[String]): IO[ExitCode] =
    for {
      chatTopic <- Topic[IO, String](initial = "Welcome to the chat!")
      _         <- BlazeServerBuilder[IO](ExecutionContext.global)
                     .bindHttp(port = 9002, host = "localhost")
                     .withHttpApp(httpApp(chatTopic))
                     .serve
                     .compile
                     .drain
    } yield ExitCode.Success
}

// Http4s does not yet provide a full-fledged WebSocket client (contributions are welcome):
// https://github.com/http4s/http4s/issues/330. However, there is a purely functional wrapper
// for the built-in JDK 11+ HTTP client available.
object WebSocketClient extends IOApp {

  private val uri = uri"ws://localhost:9002/echo"

  private def printLine(string: String = ""): IO[Unit] = IO(println(string))

  override def run(args: List[String]): IO[ExitCode] = {
    val clientResource = Resource
      .eval(IO(HttpClient.newHttpClient()))
      .flatMap(JdkWSClient[IO](_).connectHighLevel(WSRequest(uri)))

    clientResource.use { client =>
      for {
        _ <- client.send(WSFrame.Text("Hello, world!"))
        _ <- client.receiveStream
               .collectFirst {
                 case WSFrame.Text(s, _) => s
               }
               .compile
               .string >>= printLine
      } yield ExitCode.Success
    }
  }
}

// Attributions and useful links:
// https://en.wikipedia.org/wiki/WebSocket
// https://javascript.info/websocket
// https://hpbn.co/websocket/
