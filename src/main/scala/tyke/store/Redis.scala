package tyke.store

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}
import java.time.Instant
import java.util.UUID

import cats.data.OptionT
import cats.effect.IO
import com.redis.RedisClient
import com.redis.serialization.{Format, Parse}
import tyke.AuthServer.Data.UserSession
import tyke.config.RedisBackend
import tyke.store.UserTypes.User

import scala.concurrent.duration.FiniteDuration

object Redis {
  implicit private val parseUserSession: Parse[UserSession] = Parse[UserSession](deserialise)
  implicit private val parseUser: Parse[User]               = Parse[User](deserialise[User])
  implicit private val parseInstant: Parse[Instant]         = Parse[Instant](deserialise[Instant])

  implicit val format: Format = Format {
    case x: UserSession => serialise(x)
    case x: User        => serialise(x)
    case x: Instant     => serialise(x)
  }

  def redisSessionStore(
    client: RedisClient,
    mkSession: String => UserSession = UserSession,
    timeout: FiniteDuration
  ): BackingSessionStore[IO, User, UserSession] =
    new BackingSessionStore[IO, User, UserSession] {
      private def sessionKey(s: UserSession)     = s"session:${s.session}"
      private def sessionTimeKey(s: UserSession) = sessionKey(s) + ":time"

      def endAllSessions(user: User): IO[Unit] =
        // remove from all maps
        for {
          sessions <- IO(client.smembers[UserSession](user).get.map(_.get))
          // delete all user->session
          _ <- IO(client.del(user))
          // delete session->user
          _ <- IO(sessions.map(s => client.del(sessionKey(s))))
          // delete session timestamp
          _ <- IO(sessions.map(s => client.del(sessionTimeKey(s))))
        } yield ()

      def endSession(session: UserSession): IO[Unit] =
        for {
          user <- IO(client.get[User](sessionKey(session)))
          // delete session->user
          _ <- IO(user.map(_ => client.del(sessionKey(session))))
          // delete session timestamp
          _ <- IO(user.map(_ => client.del(sessionTimeKey(session))))
          // delete user->session
          _ <- IO(client.srem(user, session).get)
        } yield ()

      def newSession(user: User): IO[UserSession] = {
        val s = mkSession(UUID.randomUUID().toString)
        for {
          _ <- IO(client.sadd(user, s).get) // user->sessions
          _ <- IO(client.setex(sessionKey(s), timeout.toSeconds, user)) // session->user, these keys expire
          _ <- IO(client.setex(sessionTimeKey(s), timeout.toSeconds, Instant.now))
          _ <- IO(client.expire(user, timeout.toSeconds.toInt)) // expire user set itself
        } yield s
      }

      def validateSession(session: UserSession): OptionT[IO, UserSession] =
        for {
          // ensure session still exists
          user <- OptionT.fromOption[IO](client.get[User](sessionKey(session)))
          // update session expiration
          _ <- OptionT.pure[IO](client.expire(sessionKey(session), timeout.toSeconds.toInt))
          // update timestamp expiration
          _ <- OptionT.pure[IO](client.expire(sessionTimeKey(session), timeout.toSeconds.toInt))
          // update user->sessions expiration
          _ <- OptionT.pure[IO](client.expire(user, timeout.toSeconds.toInt)) // expire user set itself
        } yield session

      def userSessions(user: User): OptionT[IO, Set[UserSession]] =
        OptionT.fromOption[IO](client.smembers[UserSession](user)).map(_.map(_.get))

      def sessionUser(session: UserSession): OptionT[IO, User] =
        OptionT.fromOption[IO](client.get[User](sessionKey(session)))

      def sessionTimestamp(session: UserSession): OptionT[IO, Instant] =
        OptionT.fromOption[IO](client.get[Instant](sessionKey(session) + ":time"))
    }

  def redisClient(conf: RedisBackend): IO[RedisClient] = IO(new RedisClient(conf.host, conf.port, conf.database))

  private def serialise(value: Any): Array[Byte] = {
    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos                           = new ObjectOutputStream(stream)
    oos.writeObject(value)
    oos.close()
    stream.toByteArray
  }

  private def deserialise[A](bytes: Array[Byte]): A = {
    val ois   = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val value = ois.readObject
    ois.close()
    value.asInstanceOf[A]
  }
}
