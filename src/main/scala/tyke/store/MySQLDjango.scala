package tyke.store

import java.util.{Base64, UUID}

import doobie._
import doobie.implicits._
import cats._
import cats.data.OptionT
import cats.effect._
import cats.implicits._
import doobie.hikari.HikariTransactor
import tyke.config.MysqlDjango
import tyke.store.UserTypes._
import io.github.nremond.PBKDF2

object MySQLDjango {
  def djangoBackingStore(transactor: Transactor[IO]) = new BackingUserStore[IO, String, User] {

    private val algoMap = Map("pbkdf2_sha256" -> "HmacSHA256")

    // TODO: improve/rewrite
    val verifyPassword =
      (pw: Password[Source.Entered], hashedPw: Password[Source.Stored]) =>
        hashedPw.value.split("\\$").toList match {
          case algo :: iterations :: salt :: hash :: _ =>
            // like our django does it. TODO: add to configuration
            val calculatedHash =
              new String(
                Base64.getEncoder.encode(
                  PBKDF2(
                    pw.value.getBytes,
                    salt.getBytes,
                    iterations = Integer.parseInt(iterations),
                    cryptoAlgo = algoMap(algo)
                  )
                )
              )
            hash == calculatedHash
          case _ => throw new IllegalArgumentException("Unrecognized hash format")
        }

    private def doGet(id: String, pw: Option[Password[Source.Entered]]): OptionT[IO, User] = {
      val q = sql"""
      select u.username, u.password, up.role, u.is_staff, group_concat(ug.name)
      from auth_user u, auth_group ug, auth_user_groups aug, myapp_userprofile up
      where up.user_id=u.id and ug.id = aug.group_id and aug.user_id = u.id and u.username=$id
        """
      val ret = q
        .query[(Option[String], Option[String], Option[String], Option[Int], Option[String])]
        .option
        .transact(transactor)
        .map {
          case Some((Some(name), Some(storedPassword), Some(role), Some(is_staff), Some(groups))) =>
            Some(
              UserTypes.UserAndPassword(
                User(
                  username = name,
                  role = if (is_staff > 0) Role.StaffUser else Role.withNameOption(role).getOrElse(Role.Nobody),
                  groups = groups.split(",")
                ),
                password = Password[Source.Stored](storedPassword)
              )
            )
          case _ => None
        }
      OptionT(ret)
        .filter(u => {
          pw match {
            case None    => true
            case Some(p) => verifyPassword(p, u.password)
          }
        })
        .map(_.user)
    }

    def get(id: String): OptionT[IO, User] = doGet(id, None)

    def getWithPassword(id: String, password: Password[Source.Entered]): OptionT[IO, User] = doGet(id, Some(password))
  }

  def transactor(config: MysqlDjango): IO[HikariTransactor[IO]] = {
    val url = s"jdbc:mysql://${config.host}:${config.port}/${config.database}"
    HikariTransactor.newHikariTransactor[IO]("com.mysql.jdbc.Driver", url, config.user, config.password)
  }

}
