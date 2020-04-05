package tyke.store

import java.util.Properties

import cats.data.OptionT
import cats.effect.IO
import tyke.config.LdapBackend
import tyke.store.UserTypes.{Password, Role, Source, User}
import javax.naming.{Context, NamingEnumeration}
import javax.naming.directory.{InitialDirContext, SearchControls, SearchResult}

import scala.collection.JavaConverters._

object LDAP {
  def ldapBackingStore(conf: LdapBackend): BackingUserStore[IO, String, User] = new BackingUserStore[IO, String, User] {
    private def doGet(id: String, password: Option[Password[Source.Entered]]): OptionT[IO, User] =
      OptionT[IO, User](
        for {
          result <- searchLdap(id)
          _ <- password match {
            case None    => IO.pure(Unit)
            case Some(p) => validateForLdap(result.getNameInNamespace, p.value)
          }
        } yield Some(User(
          username = result.getAttributes.get("sAMAccountName").get.asInstanceOf[String],
          role = Role.StaffUser,
          groups = userGroups(result)))  // everyone in LDAP is a staffer right now, refine as needed
      )

    /**
      * Return CN groups, if any
      */
    private def userGroups(searchResult: SearchResult): Seq[String] = {
      /**
        * Attempt to find a CN in the "memberOf" string
        */
      def getName(cn: String): Option[String] = {
         val regex = """^CN=([\w\s\d]*),.*$""".r
         cn match {
            case regex(name) => Some(name)
            case _ => None
         }
      }

      val groups = searchResult.getAttributes.get("memberOf").getAll.asScala
      groups.flatMap(x => getName(x.asInstanceOf[String])).toList
    }

    def get(id: String): OptionT[IO, User] = doGet(id, None)

    def getWithPassword(id: String, password: Password[Source.Entered]): OptionT[IO, User] = doGet(id, Some(password))

    def searchLdap(username: String): IO[SearchResult] =
      IO {
        val props = new Properties
        props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
        props.put(Context.PROVIDER_URL, conf.providerUrl)
        props.put(Context.SECURITY_PRINCIPAL, conf.binddn)
        props.put(Context.SECURITY_CREDENTIALS, conf.bindpw)

        val context: InitialDirContext = new InitialDirContext(props)

        val controls: SearchControls = new SearchControls
        controls.setReturningAttributes(Array[String]("givenName", "sn", "memberOf", "cn", "sAMAccountName"))
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE)

        val answers: NamingEnumeration[SearchResult] = context.search("dc=mydomain,dc=net", s"sAMAccountName=$username", controls)
        val result: SearchResult = answers.nextElement

//        val user: String = result.getNameInNamespace
        result
      }

    def validateForLdap(user: String, password: String): IO[Unit] = IO {
        val props = new Properties
        props.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
        props.put(Context.PROVIDER_URL, conf.providerUrl)
        props.put(Context.SECURITY_PRINCIPAL, user)
        props.put(Context.SECURITY_CREDENTIALS, password)
        val context = new InitialDirContext(props)
      }
  }
}
