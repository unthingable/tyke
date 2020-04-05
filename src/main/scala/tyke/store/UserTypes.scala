package tyke.store

import cats.effect.IO
import enumeratum._
import enumeratum.EnumEntry._
import io.circe.generic.auto._
import org.http4s.circe.{jsonOf, _}

object UserTypes {
  sealed trait Role extends EnumEntry with Lowercase

  object Role extends Enum[Role] {
    val values = findValues

    case object Physician extends Role
    case object Patient   extends Role
    case object StaffUser extends Role // internal power user
    case object Nobody    extends Role
  }

  sealed trait Source
  object Source {
    sealed trait Entered extends Source
    sealed trait Stored extends Source
  }
  case class Password[Source](value: String) extends AnyVal

  case class UserCredentials(
    username: String,
    password: String)

  case class User(username: String, role: Role, groups: Seq[String] = Seq())

  case class UserAndPassword(
    user: User,
    password: Password[Source.Stored])

  case class JustUsername(username: String) extends AnyVal

  implicit val UPdecoder = jsonOf[IO, UserCredentials]
  implicit val JUdecoder = jsonOf[IO, JustUsername]
//  implicit val Udecoder = jsonOf[IO, User]
}
