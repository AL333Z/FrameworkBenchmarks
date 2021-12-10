package http4s.techempower.benchmark

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.Random
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.dsl._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.twirl._
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s._

import skunk._
import natchez.Trace.Implicits.noop

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

object WebServer extends IOApp with Http4sDsl[IO] {

    def makeDatabaseService(
      host: String,
      poolSize: Int
  ): Resource[IO, DatabaseService] = 
    for {
      pool <- Session.pooled[IO](
                host = host,
                port = 5432,
                user = "benchmarkdbuser",
                database = "hello_world",
                password = Some("benchmarkdbpass"),
                max = poolSize
              )
      random <- Resource.eval(Random.scalaUtilRandom[IO])
    } yield DatabaseService.fromPool(pool, random)

  // Add a new fortune to an existing list, and sort by message.
  def getSortedFortunes(old: List[Fortune]): List[Fortune] = {
    val newFortune = Fortune(0, "Additional fortune added at request time.")
    (newFortune :: old).sortBy(_.message)
  }

  // Add Server header container server address
  def addServerHeader(service: HttpRoutes[IO]): HttpRoutes[IO] =
    cats.data.Kleisli { req: Request[IO] =>
      service.run(req).map(_.putHeaders(server))
    }

  val server = headers.Server(ProductId("http4s-ember", None))

  // HTTP service definition
  def service(db: DatabaseService) =
    addServerHeader(
      HttpRoutes.of[IO] {
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

  // Entry point when starting service
  override def run(args: List[String]): IO[ExitCode] =
    makeDatabaseService(
        args.headOption.getOrElse("localhost"),
        sys.env.get("DB_POOL_SIZE").map(_.toInt).getOrElse(64)
      )
      .flatMap(db =>
        EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(service(db).orNotFound)
          .build
      )
      .useForever
}
