package tyke

import cats.effect.IO
import org.http4s._
import org.http4s.implicits._
import org.slf4j.LoggerFactory

import scala.collection.mutable

object Helpers {

  /**
    * Fake client with naive cookie handling
    *
    * @param defaultService
    */
  // noinspection ScalaDocMissingParameterDescription
  case class MicroClient(defaultService: HttpService[IO]) {
    val cookieJar: mutable.HashMap[String, Cookie] = mutable.HashMap.empty
    private val logger = LoggerFactory.getLogger("MicroClient")

    def fetch(req: IO[Request[IO]], service: HttpService[IO] = defaultService): Response[IO] = {
      val reqWithCookies = cookieJar.values.foldRight(req)((cookie, req) => req.map(_.addCookie(cookie)))
      val result = reqWithCookies.flatMap(service.orNotFound(_)).unsafeRunSync()

      logger.debug(result.as[String].unsafeRunSync())

      for (c <- result.cookies) {
        c.content match {
          case "" => cookieJar.remove(c.name)
          case _  => cookieJar.put(c.name, c)
        }
      }

      result
    }
  }
}
