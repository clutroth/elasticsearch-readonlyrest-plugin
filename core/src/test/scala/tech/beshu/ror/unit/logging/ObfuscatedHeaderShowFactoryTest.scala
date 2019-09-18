package tech.beshu.ror.unit.logging

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.show.ObfuscatedHeaderShowFactory

class ObfuscatedHeaderShowFactoryTest
  extends WordSpec
    with TableDrivenPropertyChecks
    with Matchers {
  import tech.beshu.ror.accesscontrol.show.logs._
  private val customHeaderName = Header.Name(NonEmptyString.unsafeFrom("CustomHeader"))
  private val secretHeaderName = Header.Name(NonEmptyString.unsafeFrom("Secret"))

  private val basicHeader = Header(Header.Name.authorization, NonEmptyString.unsafeFrom("secretButAuth"))
  private val customHeader = Header(customHeaderName, NonEmptyString.unsafeFrom("business value"))
  private val secretHeader = Header(secretHeaderName, NonEmptyString.unsafeFrom("secret"))
  "LoggingContextFactory" should {
    "create Show[Header] instance" when {
      "no configuration is provided" in {
        val table = Table(("conf", "authorization", "custom", "secret"),
          (Set.empty[Header.Name], "Authorization=secretButAuth", "CustomHeader=business value", "Secret=secret"),
          (Set(Header.Name.authorization), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=secret"),
          (Set(Header.Name.authorization, secretHeaderName), "Authorization=<OMITTED>", "CustomHeader=business value", "Secret=<OMITTED>"),
        )
        forAll(table) { (conf, authorization, custom, secret) =>
          ObfuscatedHeaderShowFactory.create(conf).show(basicHeader) shouldEqual authorization
          ObfuscatedHeaderShowFactory.create(conf).show(customHeader) shouldEqual custom
          ObfuscatedHeaderShowFactory.create(conf).show(secretHeader) shouldEqual secret
        }

      }
    }
  }

}
