package tyke

import cats.data.NonEmptyList
import tyke.store.UserTypes.UserAndPassword
import pureconfig.module.cats._ // needed for NonEmptyList

package object config {

  sealed trait BackendType

  case class DummyBackend(users: Option[Set[UserAndPassword]]) extends BackendType

  case class MysqlDjango(
    siteKey: String,
    database: String,
    host: String,
    port: Int,
    user: String,
    password: String
  ) extends BackendType

  case class LdapBackend(
    providerUrl: String,
    binddn: String,
    bindpw: String
  ) extends BackendType

  case class RedisBackend(
    host: String,
    port: Int,
    database: Int
  ) extends BackendType

  // default values provided for ease of testing
  case class ServerConfig(
    port: Int,
    host: String,
    cookieName: String,
    cookiePath: Option[String],
    userinfoHeader: String,
    secret: String
  )

  case class BackendConfig(
    backendUser: NonEmptyList[String],
    backendSession: String,
    backends: Map[String, BackendType],
    sessionTimeout: Int // seconds
  )

  case class Config(server: ServerConfig, backend: BackendConfig)

  val defaultConfig =
    Config(
      ServerConfig(
        port = 9000,
        host = "0.0.0.0",
        cookieName = "authcookie",
        cookiePath = None,
        userinfoHeader = "",
        secret = "notsosecret"
      ),
      BackendConfig(
        backendUser = NonEmptyList.of("default"),
        backendSession = "default",
        backends = Map("default" -> DummyBackend(None)),
        sessionTimeout = 600
      )
    )

  def loadConfig(): Config = pureconfig.loadConfigOrThrow[Config]
}
