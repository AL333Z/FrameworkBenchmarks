package http4s.techempower.benchmark

import cats.effect.{IO, Resource}
import cats.syntax.all._
import skunk._
import skunk.implicits._
import skunk.codec.all._
import cats.effect.std.Random

trait DatabaseService {
  def selectWorld(id: Int): IO[World]
  def selectRandomWorld: IO[World]
  def getWorlds(numQueries: Int): IO[List[World]]
  def getNewWorlds(worlds: List[World]): IO[List[World]]
  def updateWorlds(newWorlds: List[World]): IO[Int]
  def getFortunes: IO[List[Fortune]]
}

object DatabaseService {
  private val fortunes: Query[Void, Fortune] = 
    sql"""
      SELECT id, message
      FROM Fortune
    """.query(int4 ~ varchar(2048))
       .gmap[Fortune]

  private val worldById: Query[Int, World] = 
    sql"""
      SELECT id, randomNumber
      FROM World
      WHERE id = $int4
    """.query(int4 ~ int4)
       .gmap[World]

    private val updateWorld = 
      sql"""
        UPDATE World
        SET randomNumber = $int4
        WHERE id = $int4
      """.command
         .contramap[World] { case World(id, randomNumber) => randomNumber ~ id }

  def fromPool(pool:  Resource[IO, Session[IO]], random: Random[IO]) =
    new DatabaseService{
      
      // Provide a random number between 1 and 10000 (inclusive)
      val randomWorldId = 
        random.betweenInt(1, 10001)

      // Update the randomNumber field with a random number
      def updateRandomNumber(world: World): IO[World] =
        randomWorldId.map(randomId => world.copy(randomNumber = randomId))

      // Select a World object from the database by ID
      def selectWorld(id: Int): IO[World] =
        pool.use(s =>
          s.prepare(worldById)
            .use(pq =>  
              pq.unique(id)
            )
        )
        
      // Select a random World object from the database
      def selectRandomWorld: IO[World] =
        for {
          randomId <- randomWorldId
          world <- selectWorld(randomId)
        } yield world
      
      // Select a specified number of random World objects from the database
      def getWorlds(numQueries: Int): IO[List[World]] =
        selectRandomWorld.parReplicateA(numQueries)

      // Update the randomNumber field with a new random number, for a list of World objects
      def getNewWorlds(worlds: List[World]): IO[List[World]] =
        worlds.parTraverse(updateRandomNumber)
      
      // Update the randomNumber column in the database for a specified set of World objects.
      // TODO: batching
      def updateWorlds(newWorlds: List[World]): IO[Int] = 
        pool.use(s => 
          s.prepare(updateWorld)
            .use(pc => 
              newWorlds.traverse(pc.execute)
                .map(_.size)
            )
        )
      
      // Retrieve all fortunes from the database
      def getFortunes: IO[List[Fortune]] =
        pool.use(s => 
          s.execute(fortunes)
        )
      
    }
}