package tyke

import java.time.Instant

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{IO, Sync}
import cats.implicits._
import fs2.Stream
import tyke.store.UserTypes.{Password, Source, User}
import tyke.AuthServer.Data.UserSession
import tyke.config._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.higherKinds

package object store {
  private val logger = LoggerFactory.getLogger("store")

  // BackingStore copied from TSec
  trait BackingUserStore[F[_], I, V] {
    def get(id: I): OptionT[F, V]
    def getWithPassword(id: I, password: Password[Source.Entered]): OptionT[F, V]
  }

  trait MutableBackingStore[F[_], I, V] extends BackingUserStore[F, I, V] {
    def put(elem: V): F[V]

    def update(v: V): F[V]

    def delete(id: I): F[Unit]
  }

  type UserStore[F[_], U <: User] = BackingUserStore[F, String, U]

  type SessionStore[F[_]] = BackingSessionStore[F, User, UserSession]

  case class MultiStore[F[_], U <: User](stores: NonEmptyList[UserStore[F, U]])
    (implicit F: Sync[F])
    extends UserStore[F, U] {
    private def withF(f: UserStore[F, U] => OptionT[F, U]): OptionT[F, U] =
      // Attempt a method on each store, stopping after the first success
      OptionT(
        stores.map(f(_).value)
        .map(Stream.eval)
        .combineAll
        .collectFirst{case Some(x) => x}.compile.toVector.map(_.headOption))

    def get(id: String): OptionT[F, U] = withF(_.get(id))

    def getWithPassword(id: String, password: Password[Source.Entered]): OptionT[F, U] = withF(_.getWithPassword(id, password))
  }

  trait BackingSessionStore[F[_], U, S] {
    // Log out the user completely
    def endAllSessions(user: U): F[Unit]

    // Log out a particular session
    def endSession(session: S): F[Unit]

    // New sessions are only created by logging in
    def newSession(user: U): F[S]

    // Validate [and extend] session
    def validateSession(session: S): OptionT[F, S]

    // Get all user's sessions
    def userSessions(user: U): OptionT[F, Set[S]]

    // Lookup user by session
    def sessionUser(session: S): OptionT[F, U]

    def sessionTimestamp(session: S): OptionT[F, Instant]
  }

  case class BackendStores[F[_]](userStore: UserStore[F, User], sessionStore: SessionStore[F])

  /**
    * Create store instances as defined in Config.
    * Opinionated first pass, to be generalized.
    */
  def storesFromConfig(conf: BackendConfig):
    IO[BackendStores[IO]] = {
    for {
      userStoreType <- IO(conf.backendUser.map(conf.backends)) // fail if not found
      sessionStoreType <- IO(conf.backends(conf.backendSession)) // fail if not found
      _ <- IO(logger.info(s"User backend: ${conf.backendUser}"))
      _ <- IO(logger.info(s"Session backend: ${conf.backendSession}"))
      userStore <- userStoreFromType(userStoreType)
      sessionStore <- sessionStoreFromType(sessionStoreType, conf.sessionTimeout.seconds)
    } yield BackendStores(userStore, sessionStore)
  }

  def userStoreFromType(t: NonEmptyList[BackendType]): IO[UserStore[IO, User]] = t.map {
    case b: DummyBackend =>
      val store = Dummy.DummyUserStore[IO]()
      b.users match {
        case Some(users) =>
          users.map(store.put)
          logger.debug(s"Added ${users.size} users to dummy backend store")
        case None        =>
      }
      IO(store)
    case b: MysqlDjango  =>
      for {
        transactor <- MySQLDjango.transactor(b)
      } yield MySQLDjango.djangoBackingStore(transactor)
    case b: LdapBackend  => IO(LDAP.ldapBackingStore(b))
    case _: RedisBackend => IO.raiseError(new InstantiationException("not implemented"))
  }.sequence[IO, UserStore[IO, User]].map(MultiStore[IO, User])

  def sessionStoreFromType(t: BackendType, timeout: FiniteDuration): IO[SessionStore[IO]] = t match {
    case _: DummyBackend => IO(Dummy.dummySessionStore(UserSession, timeout = timeout))
    case _: MysqlDjango  => IO.raiseError(new InstantiationException("not implemented"))
    case _: LdapBackend  => IO.raiseError(new InstantiationException("not implemented"))
    case b: RedisBackend =>
      for {
        client <- Redis.redisClient(b)
      } yield Redis.redisSessionStore(client, timeout = timeout)
  }

}
