package tyke

import java.time.Instant
import java.util.Base64

import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect._
import cats.implicits._
import tyke.config.{Config, DummyBackend, ServerConfig}
import io.circe.{Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.AuthMiddleware
import org.reactormonk.{CryptoBits, PrivateKey}
import org.slf4j.LoggerFactory

import scala.language.higherKinds


object AuthServer extends Http4sDsl[IO] {
  import tyke.store.UserTypes._
  import store._

  object Data {

    /**
      * Object representing an authenticated context, passed from middleware to service
      */
    case class Context(user: User, session: UserSession, sessionTimestamp: Instant)

    case class UserSession(session: String)

    /**
      * Session information, returned to client upon /session
      */
    case class SessionInfo(session: String, username: String, role: String, timestamp: Instant)

    // just a simple toString for use in /session, TODO: improve
    implicit val dateEncoder = Encoder.instance[Instant](_.toString.asJson)

    /**
      * User info, injected into downstream request via header
      */
    case class UserInfo(username: String, role: String)

    def toSessionInfo(ctx: Context) = SessionInfo(
      ctx.session.session,
      ctx.user.username,
      ctx.user.role.entryName,
      ctx.sessionTimestamp
    )

    trait ServiceMaker {
      val conf   : ServerConfig
      val service: HttpService[IO]
    }
  }
  import Data._

  class NaiveAuth(val conf: ServerConfig) extends ServiceMaker {

    val service: HttpService[IO] = HttpService[IO] {
      case req @ POST -> Root / "login"   => login(req)
      case req @ GET -> Root / "validate" => validate(req)
      case req @ GET -> Root / "logout"   => logout(req)
      case req @ GET -> Root / "session"  => sessionInfo(req)
    }

    private def withCookie(cookieName: String, req: Request[IO]): Option[Cookie] = {
      val cookies: Option[headers.Cookie] = headers.Cookie.from(req.headers)
      cookies.flatMap(_.values.find(_.name == cookieName))
    }

    private def withAuthCookieF(cookieName: String, f: Cookie => IO[Response[IO]])(req: Request[IO]) = {
      withCookie(cookieName, req) match {
        case Some(c) => f(c)
        case None    => Forbidden("missing authorization")
      }
    }

    private def withAuthCookie(cookieName: String, f: => IO[Response[IO]]) = withAuthCookieF(cookieName, _ => f) _

    private def login(req: Request[IO]) =
      for {
        user <- req.as[UserCredentials]
        resp <- Ok(s"HELLO {user.username}").map(_.addCookie(conf.cookieName, user.username))
      } yield resp


    private def sessionInfo(req: Request[IO]) =
      withAuthCookie(conf.cookieName, Ok("go on"))(req)

    private def validate(req: Request[IO]) =
      withAuthCookie(conf.cookieName, Ok("go on"))(req)

    private def logout(req: Request[IO]) =
      withAuthCookie(conf.cookieName, Ok("see ya"))(req).removeCookie(conf.cookieName)

  }

  class AuthWithStore(
    val userStore       : BackingUserStore[IO, String, User]         = Dummy.DummyUserStore(),
    val sessionStore    : BackingSessionStore[IO, User, UserSession] = Dummy.dummySessionStore(UserSession),
    override val conf   : ServerConfig
  ) extends NaiveAuth(conf = conf) {
    import OptionT.{liftF => lo}
    import Middleware._

    private val logger = LoggerFactory.getLogger("AuthWithStore")

    val loginService: HttpService[IO] = HttpService[IO] {
      case req @ POST -> Root / "login" => login(req)
    }

    val authedService: AuthedService[Context, IO] = AuthedService[Context, IO] {
      case req @ GET -> Root / "validate" as ctx => validate(ctx)
      case req @ GET -> Root / "logout" as ctx   => logout(ctx)
      case req @ GET -> Root / "session" as ctx  => sessionInfo(ctx)
    }
    val adminService: AuthedService[Context, IO] = AuthedService[Context, IO] {
      case req @ POST -> Root / "logout_user" as ctx => logoutUser(ctx, req)
      case req @ POST -> Root / "escalate" as ctx    => escalateToUser(ctx, req)
    }

    override val service: HttpService[IO] =
      loginService <+>
        authMiddleware(
          authResponseMiddleware(
            authedService <+> adminMiddleware(adminService)))

    private val crypto = CryptoBits(PrivateKey(scala.io.Codec.toUTF8(conf.secret)))

    private object Middleware {

      def authUser: Kleisli[IO, Request[IO], Either[String, Context]] = Kleisli({ request =>
        val sessionToken: Either[String, UserSession] = for {
          header <- headers.Cookie.from(request.headers).toRight("Cookie parsing error")
          cookie <- header.values.toList.find(_.name == conf.cookieName).toRight("Couldn't find the authcookie")
          token <- crypto.validateSignedToken(cookie.content).toRight("Cookie invalid")
        } yield UserSession(token)
        val context: EitherT[IO, String, Context] = for {
          token <- EitherT.fromEither[IO](sessionToken)
          user <- sessionStore.sessionUser(token).toRight("Invalid session")
          newSession <- sessionStore.validateSession(token).toRight("Invalid session")
          timestamp <- sessionStore.sessionTimestamp(token).toRight("Missing session timestamp?")
        } yield Context(user, newSession, timestamp)
        context.value
      })

      // maybe TODO: use proper response codes for authZ/authN (irrelevant since nginx doesn't care)
      val onFailure: AuthedService[String, IO] = Kleisli(req => OptionT.liftF(Forbidden(req.authInfo)))

      /**
        * Inject things into the response after the service has run.
        */
      def authResponseMiddleware(service: AuthedService[Context, IO]): AuthedService[Context, IO] =
        Kleisli { request =>
          service(request).map(
            Function.chain[Response[IO]](Seq(
              // forceSessionCookie(request.authInfo),
              userInfoHeader(request.authInfo)
            ))
          )
        }

      /**
        * Renew session cookie from context
        */
      private def forceSessionCookie(ctx: Context): Response[IO] => Response[IO] = { resp =>
        if (resp.headers.exists(_.value.contains(conf.cookieName)))
          resp // Underlying service already set a cookie, do not touch
        else
          resp.addCookie(cookieFromSession(ctx.session))
      }

      private def userInfoHeader(ctx: Context): Response[IO] => Response[IO] =
        _.putHeaders(Header(conf.userinfoHeader, userInfoFromContext(ctx)))

      def adminMiddleware(service: AuthedService[Context, IO]): AuthedService[Context, IO] =
        Kleisli { request =>
          if (request.authInfo.user.role != Role.StaffUser)
            OptionT.liftF(Forbidden("Not allowed"))
          else
            service(request)
        }

      val authMiddleware: AuthMiddleware[IO, Context] = AuthMiddleware(authUser, onFailure)
    }

    private def cookieFromSession(session: UserSession) =
      Cookie(
        name = conf.cookieName,
        content = crypto.signToken(session.session, java.time.Clock.systemUTC.millis.toString),
        path = conf.cookiePath)

    private def userInfoFromContext(ctx: Context): String =
      s"${ctx.user.username}:${ctx.user.role.entryName}:${ctx.user.groups.mkString(",")}"

    def doLogin(user: User): IO[Response[IO]] =
      for {
        session <- sessionStore.newSession(user)
        resp    <- Ok(s"HELLO ${user.username}").map(_.addCookie(cookieFromSession(session)))
        _ <- IO(logger.debug(s"Logging in: ${user.username} with ${session.session}"))
      } yield resp

    def login(req: Request[IO]): IO[Response[IO]] =
      (
        for {
          incoming <- lo(req.as[UserCredentials])
          user  <- userStore.getWithPassword(incoming.username, Password[Source.Entered](incoming.password))
          resp  <- lo(doLogin(user))
          _ <- OptionT.pure[IO](logger.debug(s"Logged in: ${user.username}"))
        } yield resp)
        .getOrElseF(
          Forbidden("invalid user").removeCookie(conf.cookieName)
      )

    def sessionInfo(ctx: Context): IO[Response[IO]] =
      Ok(toSessionInfo(ctx).asJson)

    def validate(ctx: Context): IO[Response[IO]] = {
      logger.debug(s"Validate ${ctx.user.username}")
      Ok("you good")
    } // session is already validated by middleware

    def logout(ctx: Context): IO[Response[IO]] =
      for {
        _    <- sessionStore.endSession(ctx.session)
        resp <- Ok("Goodbye.").removeCookie(conf.cookieName)
        _ <- IO(logger.debug(s"Logged in: ${ctx.user.username}"))
      } yield resp

    def logoutUser(ctx: Context, req: AuthedRequest[IO, _]): IO[Response[IO]] =
      (for {
        username <- lo(req.req.as[JustUsername])
        user     <- userStore.get(username.username).filter(_.role != Role.StaffUser)
        _        <- lo(sessionStore.endAllSessions(user))
        resp     <- lo(Ok(s"Logged out user ${user.username}"))
        _ <- OptionT.pure[IO](logger.debug(s"Forced logout: ${user.username} by ${ctx.user.username}"))
      } yield resp)
        .getOrElseF(Forbidden("Not allowed"))

    def escalateToUser(ctx: Context, req: AuthedRequest[IO, _]): IO[Response[IO]] =
      (for {
        username <- lo(req.req.as[JustUsername])
        user     <- userStore.get(username.username).filter(_.role != Role.StaffUser)
        resp     <- lo(doLogin(user))
        _ <- OptionT.pure[IO](logger.debug(s"Escalate: ${user.username} by ${ctx.user.username}"))
      } yield resp)
        .getOrElseF(Forbidden("Not allowed"))
  }
}

object DummyService extends Http4sDsl[IO] {
  val dummyService = HttpService[IO] {
    case req @ GET -> Root / "echo" => Ok(req.headers.toString())
  }
}
