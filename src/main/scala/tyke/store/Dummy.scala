package tyke.store

import java.time.Instant
import java.util.UUID

import cats.Monad
import cats.data.OptionT
import cats.effect.Sync
import tyke.store.UserTypes.{Password, Source, User, UserAndPassword}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}

object Dummy {
  case class DummyUserStore[F[_]]()(implicit F: Sync[F])
      extends UserStore[F, User] {
    val storageMap = mutable.HashMap.empty[String, UserAndPassword]

    def get(id: String): OptionT[F, User] =
      OptionT.fromOption[F](storageMap.get(id).map(_.user))

    def getWithPassword(id: String, password: Password[Source.Entered]): OptionT[F, User] =
      OptionT.fromOption[F](storageMap.get(id).filter(_.password == password).map(_.user))

    def put(elem: UserAndPassword): F[UserAndPassword] = {
      val map = storageMap.put(elem.user.username, elem)
      if (map.isEmpty)
        F.pure(elem)
      else
        F.raiseError(new IllegalArgumentException)
    }
  }

  def dummySessionStore[F[_], U, S](
    mkSession: String => S,
    timeout: Duration = 1000 hours,
    now: => Instant = Instant.now()  // virtualizeable time for testing
  )(implicit F: Monad[F]): BackingSessionStore[F, U, S] =
    new BackingSessionStore[F, U, S] {
      private val userSessionMap = new mutable.HashMap[U, mutable.Set[S]] with mutable.MultiMap[U, S]
      private val sessionUserMap = mutable.HashMap.empty[S, U]
      private val timeMap = mutable.HashMap.empty[S, Instant]

      def endAllSessions(user: U): F[Unit] = {
        // remove from both maps
        userSessionMap.remove(user).map(_.map(session => {
          sessionUserMap.remove(session)
          timeMap.remove(session)
        }))
        F.unit
      }

      def endSession(session: S): F[Unit] = {
        val user = sessionUserMap.remove(session)
        timeMap.remove(session)
        user match {
          case None    => F.unit
          case Some(u) => userSessionMap.removeBinding(u, session); F.unit
        }
      }

      def newSession(user: U): F[S] = {
        val s = mkSession(UUID.randomUUID().toString)
        sessionUserMap.put(s, user)
        userSessionMap.addBinding(user, s)
        timeMap.put(s, now)
        F.pure(s)
      }

      def validateSession(session: S): OptionT[F, S] = {
        val ret = Some(session)
          .filter(sessionUserMap.contains)
          // ignore expired sessions (to be cleanup up by another thread)
          .filter(isValid)
        ret.map(timeMap.put(_, now)) // renew session timestamp
        OptionT.fromOption(ret)
      }
      /* If we wanted renewable sessions:
        for {
          user <- sessionUser(session)
          _ <- OptionT.liftF[F, Unit](endSession(session))
          ns <- OptionT.liftF[F, S](newSession(user))
        } yield ns
      // Should this be a raised error instead of None?
      */

      private def isValid: S => Boolean = {
        timeMap.get(_).exists(_.plusNanos(timeout.toNanos).isAfter(now))
      }

      def userSessions(user: U): OptionT[F, Set[S]] =
        OptionT.fromOption[F](userSessionMap.get(user).map(_.toSet))

      def sessionUser(session: S): OptionT[F, U] =
        OptionT.fromOption(sessionUserMap.get(session))

      def sessionTimestamp(session: S): OptionT[F, Instant] =
        OptionT.fromOption(timeMap.get(session))
    }
}
