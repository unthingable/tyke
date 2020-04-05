package tyke

import cats.effect.IO
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl
import org.http4s.{Cookie, HttpService, Status, Uri, headers}
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
//noinspection Specs2Matchers
class HelperSpec extends org.specs2.mutable.Specification with Http4sClientDsl[IO] with Http4sDsl[IO] {
  sequential

  val testCookie = Cookie("testcookie", "testvalue")

  private val service = HttpService[IO] {
    case req @ GET -> Root / "setcookie" =>
      Ok().addCookie(testCookie)
    case req @ GET -> Root / "clearcookie" =>
      Ok().removeCookie(testCookie.name)
    case req @ GET -> Root / "withcookie" =>
      val cookies: Option[headers.Cookie] = headers.Cookie.from(req.headers)
      cookies.flatMap(_.values.find(_.name == testCookie.name)) match {
        case Some(c) => Ok()
        case None => BadRequest()
      }
  }

  "MicroClient" >> {
    val client = Helpers.MicroClient(service)

    "cookie machine" >> {
      val resp = client.fetch(GET(Uri.uri("/withcookie")))
      resp.status mustNotEqual Status.Ok
    }
    "set cookie" >> {
      client.fetch(GET(Uri.uri("/setcookie")))
      client.cookieJar.get(testCookie.name).map(_.content) mustEqual Some(testCookie.content)
    }
    "with cookie" >> {
      val resp = client.fetch(GET(Uri.uri("/withcookie")))
      resp.status mustEqual Status.Ok
    }
    "clear cookie" >> {
      client.fetch(GET(Uri.uri("/clearcookie")))
      client.cookieJar.get("testcookie") mustEqual None
    }
    "no cookie again" >> {
      val resp = client.fetch(GET(Uri.uri("/withcookie")))
      resp.status mustNotEqual Status.Ok
    }
  }
}
