package tyke

import cats.effect.IO
import tyke.config._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.{Status, Uri}
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class NaiveAuthSpec extends org.specs2.mutable.Specification with Http4sClientDsl[IO] {
  sequential

  import AuthServer._
  import tyke.store.UserTypes._

  private val serviceMaker = new AuthServer.NaiveAuth(conf = defaultConfig.server)
  private val service      = serviceMaker.service
  private val client       = Helpers.MicroClient(service)

  "NaiveAuth" >> {
    "empty login" >> {
      val resp = client.fetch(GET(Uri.uri("/login")))
      resp.status mustNotEqual Status.Ok
    }
    "valid login" >> {
      val resp = client.fetch(POST(Uri.uri("/login"), UserCredentials("someuser", "somepass").asJson))
      resp.status mustEqual Status.Ok
      client.cookieJar must haveKey(serviceMaker.conf.cookieName)
    }
    "valid logout" >> {
      val resp = client.fetch(GET(Uri.uri("/logout")))
      resp.status mustEqual Status.Ok
      client.cookieJar must not haveKey serviceMaker.conf.cookieName
    }
    "empty logout" >> {
      val resp = client.fetch(GET(Uri.uri("/logout")))
      resp.status mustNotEqual Status.Ok
    }
  }
}
