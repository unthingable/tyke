package tyke

import java.time.Instant

import cats.effect.IO
import tyke.AuthServer.Data.UserSession
import tyke.config._
import tyke.store.Dummy
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.dsl.Http4sClientDsl
import org.junit.runner.RunWith
import org.specs2.specification.BeforeEach

import scala.concurrent.duration._
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AuthSessionSpec extends org.specs2.mutable.Specification with BeforeEach with Http4sClientDsl[IO] {
  sequential

  import AuthServer._
  import tyke.store.UserTypes._

  def before = virtualOffset = 0.seconds; println(">> resetting clock")

  private var virtualOffset = 0.seconds
  private def virtualNow()  = Instant.now().plusNanos(virtualOffset.toNanos)
  private val userStore     = Dummy.DummyUserStore[IO]()
  private val sessionStore =
    Dummy.dummySessionStore[IO, User, UserSession](UserSession, timeout = 1.second, now = virtualNow)
  private val serviceMaker =
    new AuthWithStore(conf = defaultConfig.server, userStore = userStore, sessionStore = sessionStore)
  private val service = serviceMaker.service
  private val client  = Helpers.MicroClient(service)

  val user1      = UserAndPassword(User("user1", Role.Patient), Password("pass1"))
  val user2      = UserAndPassword(User("user2", Role.Physician), Password("pass2"))
  val staffUser  = UserAndPassword(User("admin", Role.StaffUser), Password("pass3"))
  val staffUser2 = UserAndPassword(User("admin2", Role.StaffUser), Password("pass3"))

  userStore.put(user1)
  userStore.put(user2)
  userStore.put(staffUser)

  val validateUri: IO[Request[IO]] = GET(Uri.uri("/validate"))
  val sessionUri: IO[Request[IO]]  = GET(Uri.uri("/session"))
  val logoutUri: IO[Request[IO]]   = GET(Uri.uri("/logout"))

  "clock test" >> {
    virtualOffset += 10.seconds
    val fakeNow = virtualNow()
    virtualOffset = 0.seconds
    val realNow = virtualNow()
    realNow.isBefore(fakeNow) mustEqual true
  }
  "session" >> {
    (
      for {
        session <- sessionStore.newSession(user1.user)
        user    <- sessionStore.sessionUser(session).value
      } yield user must beSome(user1.user)
    ).unsafeRunSync()
  }
  "AuthWithSessions" >> {
    "happy path" >> {
      client
        .fetch(POST(Uri.uri("/login"), UserCredentials(user1.user.username, user1.password.value).asJson))
        .status mustEqual Status.Ok
      client.fetch(validateUri).status mustEqual Status.Ok
      client.fetch(sessionUri).status mustEqual Status.Ok
      client.fetch(logoutUri).status mustEqual Status.Ok
      client.cookieJar must beEmpty
    }
    "session hijack" >> {
      client.fetch(POST(Uri.uri("/login"), UserCredentials(user1.user.username, user1.password.value).asJson))
      val cookies = client.cookieJar.toMap
      client.fetch(logoutUri)
      client.cookieJar ++= cookies
      client.cookieJar must haveKey(serviceMaker.conf.cookieName)
      client.fetch(validateUri).status mustNotEqual Status.Ok
    }
    "empty login" >> {
      val resp = client.fetch(GET(Uri.uri("/login")))
      resp.status mustNotEqual Status.Ok
      client.fetch(validateUri).status mustEqual Status.Forbidden
    }
    "invalid login" >> {
      val resp = client.fetch(POST(Uri.uri("/login"), UserCredentials("someuser", "somepass").asJson))
      resp.status mustEqual Status.Forbidden
      client.cookieJar must not haveKey serviceMaker.conf.cookieName
    }
    "valid login" >> {
      val resp =
        client.fetch(POST(Uri.uri("/login"), UserCredentials(user1.user.username, user1.password.value).asJson))
      resp.status mustEqual Status.Ok
      client.cookieJar must haveKey(serviceMaker.conf.cookieName)
      serviceMaker.sessionStore.userSessions(user1.user).value.unsafeRunSync() must beSome[Iterable[UserSession]]
    }
    "unauthorized access" >> {
      client.fetch(validateUri).status mustEqual Status.Ok
      client
        .fetch(POST(Uri.uri("/logout_user"), JustUsername(user2.user.username).asJson))
        .status mustEqual Status.Forbidden
    }
    "logout user" >> {
      val adminClient = Helpers.MicroClient(service)
      adminClient
        .fetch(POST(Uri.uri("/login"), UserCredentials(staffUser.user.username, staffUser.password.value).asJson))
        .status mustEqual Status.Ok
      adminClient
        .fetch(POST(Uri.uri("/logout_user"), JustUsername(user1.user.username).asJson))
        .status mustEqual Status.Ok
      client.fetch(validateUri).status mustEqual Status.Forbidden
    }
    "escalate" >> {
      // TODO: improve test
      val adminClient = Helpers.MicroClient(service)
      adminClient
        .fetch(POST(Uri.uri("/login"), UserCredentials(staffUser.user.username, staffUser.password.value).asJson))
        .status mustEqual Status.Ok
      adminClient
        .fetch(POST(Uri.uri("/escalate"), JustUsername(staffUser2.user.username).asJson))
        .status mustEqual Status.Forbidden
      adminClient
        .fetch(POST(Uri.uri("/logout_user"), JustUsername(staffUser2.user.username).asJson))
        .status mustEqual Status.Forbidden
      adminClient.fetch(POST(Uri.uri("/escalate"), JustUsername(user1.user.username).asJson)).status mustEqual Status.Ok
      adminClient
        .fetch(POST(Uri.uri("/logout_user"), JustUsername(user1.user.username).asJson))
        .status mustEqual Status.Forbidden
      adminClient
        .fetch(POST(Uri.uri("/escalate"), JustUsername(user1.user.username).asJson))
        .status mustEqual Status.Forbidden
    }
    "session timeout" >> {
      client
        .fetch(POST(Uri.uri("/login"), UserCredentials(user1.user.username, user1.password.value).asJson))
        .status mustEqual Status.Ok
      client.fetch(validateUri).status mustEqual Status.Ok
      virtualOffset += 10.seconds
      client.fetch(validateUri).status mustNotEqual Status.Ok
      virtualOffset = 0.seconds
      client.fetch(validateUri).status mustEqual Status.Ok
    }
  }
}
