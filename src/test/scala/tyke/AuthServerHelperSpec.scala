package tyke

import cats.Id
import org.junit.runner.RunWith

import scala.collection.mutable
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
//noinspection Specs2Matchers
class AuthServerHelperSpec extends org.specs2.mutable.Specification {
  sequential

  import store._

  val user1                                              = 1
  val user2                                              = 2
  val sessionStore: BackingSessionStore[Id, Int, String] = Dummy.dummySessionStore(identity)

  "dummySessionStore" >> {
    val s1 = sessionStore.newSession(user1)
    val s2 = sessionStore.newSession(user1)

    s1 mustNotEqual s2
    // need to flip OptionT to use the beSome matcher
    sessionStore.userSessions(user1).value mustEqual Some(mutable.Set(s1, s2))
    sessionStore.userSessions(user2).value mustEqual None

    val s3 = sessionStore.newSession(user2)
    sessionStore.userSessions(user1).value mustEqual Some(mutable.Set(s1, s2))
    sessionStore.userSessions(user2).value mustEqual Some(mutable.Set(s3))

    sessionStore.sessionUser(s2).value must beSome(user1)
    sessionStore.sessionUser(s3).value must beSome(user2)

    sessionStore.validateSession(s3).value must beSome[String]
    sessionStore.userSessions(user1).map(_.size).value must beSome(2)
    sessionStore.userSessions(user2).map(_.size).value must beSome(1)

    sessionStore.endAllSessions(user1)
    sessionStore.userSessions(user1).value must beNone
    sessionStore.userSessions(user2).map(_.size).value must beSome(1)

    sessionStore.validateSession(s1).value must beNone
  }
}
