package tyke

import cats.effect._
import fs2.{Stream, StreamApp}
import tyke.config.{loadConfig, Config}
import tyke.store._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder

import scala.concurrent.ExecutionContext.Implicits.global

object AuthApp extends StreamApp[IO] with Http4sDsl[IO] {

  def stream(args: List[String], requestShutdown: IO[Unit]) =
    for {
      config   <- Stream.eval(IO(loadConfig()))
      stores   <- Stream.eval(storesFromConfig(config.backend))
      exitCode <- blazeBuilder(stores = stores, conf = config).serve
    } yield exitCode

  def blazeBuilder(stores: BackendStores[IO], conf: Config) =
    BlazeBuilder[IO]
      .bindHttp(conf.server.port, conf.server.host)
      .mountService(
        new AuthServer.AuthWithStore(
          userStore = stores.userStore,
          sessionStore = stores.sessionStore,
          conf = conf.server
        ).service,
        "/"
      )
      .mountService(DummyService.dummyService, "/api")
}
