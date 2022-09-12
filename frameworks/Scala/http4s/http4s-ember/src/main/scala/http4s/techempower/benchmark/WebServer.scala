package http4s.techempower.benchmark

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.Random
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe._
import org.http4s.twirl._
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s._
import skunk._
import natchez.http4s.NatchezMiddleware
import cats.effect.kernel._
import cats._
import cats.effect.std.Console
import cats.data._
import fs2.io.net.Network
import cats.implicits._
import org.http4s.implicits._
import natchez._
import org.http4s.server.middleware.ErrorHandling

final case class Message(message: String)
final case class World(id: Int, randomNumber: Int)
final case class Fortune(id: Int, message: String)

// Extract queries parameter (with default and min/maxed)
object Queries {
  def unapply(params: Map[String, Seq[String]]): Option[Int] =
    Some(params.getOrElse("queries", Nil).headOption match {
      case None => 1
      case Some(x) =>
        Math.max(1, Math.min(500, scala.util.Try(x.toInt).getOrElse(1)))
    })
}

object WebServer extends IOApp {

  private val serverHeader = headers.Server(ProductId("http4s-ember", None))

  def databasePoolResource[F[_]: Async: Trace: Network: Console: Parallel](
      host: String
  ): Resource[F, DatabaseService[F]] = 
    for {
      pool <- Session.pooled[F](
                host = host,
                port = 5432,
                user = "benchmarkdbuser",
                database = "hello_world",
                password = Some("benchmarkdbpass"),
                max = 10
              )
      random <- Resource.eval(Random.scalaUtilRandom[F])
    } yield DatabaseService.fromPool(pool, random)

  // Add a new fortune to an existing list, and sort by message.
  def getSortedFortunes(old: List[Fortune]): List[Fortune] = {
    val newFortune = Fortune(0, "Additional fortune added at request time.")
    (newFortune :: old).sortBy(_.message)
  }

  // Add Server header container server address
  def addServerHeader[F[_]: Functor](service: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req: Request[F] =>
      service.run(req).map(_.putHeaders(serverHeader))
    }

  // HTTP service definition
  def service[F[_]: Trace : Async: Monad](db: DatabaseService[F]): HttpRoutes[F] = {
    object dsl extends Http4sDsl[F]; import dsl._
    addServerHeader(
      HttpRoutes.of[F] { 
          case GET -> Root / "plaintext" =>
            Ok("Hello, World!")

          case GET -> Root / "json" =>
            Ok(Message("Hello, World!").asJson)

          case GET -> Root / "db" =>
            Ok(db.selectRandomWorld.map(_.asJson))

          case GET -> Root / "queries" :? Queries(numQueries) =>
            Ok(db.getWorlds(numQueries).map(_.asJson))

          case GET -> Root / "fortunes" =>
            Ok(for {
              oldFortunes <- db.getFortunes
              newFortunes = getSortedFortunes(oldFortunes)
            } yield html.index(newFortunes))
          
          case GET -> Root / "updates" :? Queries(numQueries) =>
            Ok(for {
              worlds <- db.getWorlds(numQueries)
              newWorlds <- db.getNewWorlds(worlds)
              _ <- db.updateWorlds(newWorlds)
            } yield newWorlds.asJson)
        }
      )
  }

  def routesResource[F[_]: Async: Trace: Network: Console : Parallel](dbHost: String): Resource[F, HttpRoutes[F]] =
    databasePoolResource[F](dbHost)
    .map(service(_))
    .map(ErrorHandling.httpRoutes(_))
    .map(NatchezMiddleware.server(_))

  def routes[F[_]: Async: Console: Parallel](dbHost: String): Resource[F, HttpRoutes[F]] = {
      import natchez.Trace.Implicits.noop
      routesResource(dbHost)
    }

  def server[F[_]: Async: Console: Parallel](dbHost: Option[String]): Resource[F, ExitCode] =
    for {
      routes <- routes(dbHost.getOrElse("localhost"))
      _  <- EmberServerBuilder
              .default[F]
              .withHost(ipv4"0.0.0.0")
              .withPort(port"8080")
              .withHttpApp(routes.orNotFound)
              .build
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] =
    server[IO](args.headOption).useForever

}
