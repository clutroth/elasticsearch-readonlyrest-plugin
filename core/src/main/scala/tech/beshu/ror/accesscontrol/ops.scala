/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Order, Show}
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import eu.timepit.refined.types.string.NonEmptyString
import shapeless.Nat
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched
import tech.beshu.ror.accesscontrol.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.Block.{History, HistoryItem, Name, Policy}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ProxyAuth, UserDef}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, RuleOrdering, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.RulesValidator.ValidationError
import tech.beshu.ror.accesscontrol.header.ToHeaderValue
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.FilterTransient

import scala.concurrent.duration.FiniteDuration
import scala.language.{implicitConversions, postfixOps}

object header {

  class FlatHeader(val header: Header) extends AnyVal {
    def flatten: String = s"${header.name.value.value.toLowerCase()}:${header.value}"
  }
  object FlatHeader {
    implicit def from(header: Header): FlatHeader = new FlatHeader(header)
  }

  class ToTuple(val header: Header) extends AnyVal {
    def toTuple: (String, String) = (header.name.value.value, header.value.value)
  }
  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

  trait ToHeaderValue[T] {
    def toRawValue(t: T): NonEmptyString
  }
  object ToHeaderValue {
    def apply[T](func: T => NonEmptyString): ToHeaderValue[T] = (t: T) => func(t)
  }
}

object orders {
  implicit val nonEmptyStringOrder: Order[NonEmptyString] = Order.by(_.value)
  implicit val headerNameOrder: Order[Header.Name] = Order.by(_.value.value)
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value.value))
  implicit val addressOrder: Order[Address] = Order.by {
    case Address.Ip(value) => value.toString()
    case Address.Name(value) => value.toString
  }
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val userIdOrder: Order[User.Id] = Order.by(_.value)
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by(_.value)
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val aDocumentFieldOrder: Order[ADocumentField] = Order.by(_.value)
  implicit val negatedDocumentFieldOrder: Order[NegatedDocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[PlainTextSecret] = Order.by(_.value)
  implicit val indexOrder: Order[IndexName] = Order.by(_.value)
  implicit val userDefOrder: Order[UserDef] = Order.by(_.id.value)
  implicit val ruleNameOrder: Order[Rule.Name] = Order.by(_.value)
  implicit val ruleOrder: Order[Rule] = Order.fromOrdering(new RuleOrdering)
  implicit val forbiddenByMismatchedCauseOrder: Order[ForbiddenByMismatched.Cause] = Order.by {
    case ForbiddenByMismatched.Cause.OperationNotAllowed => 1
    case ForbiddenByMismatched.Cause.ImpersonationNotAllowed => 2
    case ForbiddenByMismatched.Cause.ImpersonationNotSupported => 3
  }
}

object show {

  object logs {
    implicit val nonEmptyStringShow: Show[NonEmptyString] = Show.show(_.value)
    implicit val userIdShow: Show[User.Id] = Show.show(_.value.value)
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show {
      case Address.Ip(value) => value.toString
      case Address.Name(value) => value.toString
    }
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val jsonPathShow: Show[JsonPath] = Show.show(_.getPath)
    implicit val uriShow: Show[Uri] = Show.show(_.toJavaUri.toString())
    implicit val headerNameShow: Show[Header.Name] = Show.show(_.value.value)
    implicit val headerShow: Show[Header] = Show.show {
      case Header(name, _) if name === Header.Name.authorization => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.value.show}"
    }
    implicit val documentFieldShow: Show[DocumentField] = Show.show {
      case f: ADocumentField => f.value
      case f: NegatedDocumentField => s"~${f.value}"
    }
    implicit val kibanaAppShow: Show[KibanaApp] = Show.show(_.value.value)
    implicit val proxyAuthNameShow: Show[ProxyAuth.Name] = Show.show(_.value)
    implicit val indexNameShow: Show[IndexName] = Show.show(_.value.value)
    implicit val externalAuthenticationServiceNameShow: Show[ExternalAuthenticationService.Name] = Show.show(_.value)
    implicit val groupShow: Show[Group] = Show.show(_.value.value)
    implicit val tokenShow: Show[AuthorizationToken] = Show.show(_.value.value)
    implicit val jwtTokenShow: Show[JwtToken] = Show.show(_.value.value)
    implicit val uriPathShow: Show[UriPath] = Show.show(_.value)
    implicit val dnShow: Show[Dn] = Show.show(_.value.value)
    implicit val envNameShow: Show[EnvVarName] = Show.show(_.value.value)
    implicit val propNameShow: Show[PropName] = Show.show(_.value.value)
    implicit val blockContextShow: Show[BlockContext] = Show.show { bc =>
      (showOption("user", bc.loggedUser) ::
        showOption("group", bc.currentGroup) ::
        showTraversable("av_groups", bc.availableGroups) ::
        showTraversable("indices", bc.indices.getOrElse(Set.empty)) ::
        showOption("kibana_idx", bc.kibanaIndex) ::
        showTraversable("response_hdr", bc.responseHeaders) ::
        showTraversable("context_hdr", bc.contextHeaders) ::
        showTraversable("repositories", bc.repositories.getOrElse(Set.empty)) ::
        showTraversable("snapshots", bc.snapshots.getOrElse(Set.empty)) ::
        Nil flatten) mkString ";"
    }
    private implicit val kibanaAccessShow: Show[KibanaAccess] = Show {
      case KibanaAccess.RO => "ro"
      case KibanaAccess.ROStrict => "ro_strict"
      case KibanaAccess.RW => "rw"
      case KibanaAccess.Admin => "admin"
    }
    private implicit val userOriginShow: Show[UserOrigin] = Show.show(_.value.value)
    implicit val userMetadataShow: Show[UserMetadata] = Show.show { u =>
      (showOption("user", u.loggedUser) ::
        showOption("curr_group", u.currentGroup) ::
        showTraversable("av_groups", u.availableGroups) ::
        showOption("kibana_idx", u.foundKibanaIndex) ::
        showTraversable("hidden_apps", u.hiddenKibanaApps) ::
        showOption("kibana_access", u.kibanaAccess) ::
        showOption("user_origin", u.userOrigin) ::
        Nil flatten) mkString ";"
    }
    implicit val blockNameShow: Show[Name] = Show.show(_.value)
    implicit val historyItemShow: Show[HistoryItem] = Show.show { hi =>
      s"${hi.rule.show}->${
        hi.result match {
          case RuleResult.Fulfilled(_) => "true"
          case RuleResult.Rejected(_) => "false"
        }
      }"
    }
    implicit val historyShow: Show[History] = Show.show { h =>
      s"""[${h.block.show}-> RULES:[${h.items.map(_.show).mkString(", ")}], RESOLVED:[${h.blockContext.show}]]"""
    }
    implicit val policyShow: Show[Policy] = Show.show {
      case Allow => "ALLOW"
      case Forbid => "FORBID"
    }
    implicit val blockShow: Show[Block] = Show.show { b =>
      s"{ name: '${b.name.show}', policy: ${b.policy.show}, rules: [${b.rules.map(_.name.show).toList.mkString(",")}]"
    }
    implicit val runtimeResolvableVariableCreationErrorShow: Show[RuntimeResolvableVariableCreator.CreationError] = Show.show {
      case RuntimeResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
        "Cannot use multi value variable in non-array context"
      case RuntimeResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
        "Cannot use more than one multi-value variable"
      case RuntimeResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
        s"Variable malformed, cause: $cause"
      case RuntimeResolvableVariableCreator.CreationError.VariableConversionError(cause) =>
        cause
    }
    implicit val startupResolvableVariableCreationErrorShow: Show[StartupResolvableVariableCreator.CreationError] = Show.show {
      case StartupResolvableVariableCreator.CreationError.CannotUserMultiVariableInSingleVariableContext =>
        "Cannot use multi value variable in non-array context"
      case StartupResolvableVariableCreator.CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition =>
        "Cannot use more than one multi-value variable"
      case StartupResolvableVariableCreator.CreationError.InvalidVariableDefinition(cause) =>
        s"Variable malformed, cause: $cause"
    }
    def blockValidationErrorShow(block: Block.Name): Show[ValidationError] = Show.show {
      case ValidationError.AuthorizationWithoutAuthentication =>
        s"The '${block.show}' block contains an authorization rule, but not an authentication rule. This does not mean anything if you don't also set some authentication rule."
      case ValidationError.KibanaAccessRuleTogetherWithActionsRule =>
        s"The '${block.show}' block contains Kibana Access Rule and Actions Rule. These two cannot be used together in one block."
    }
    private def showTraversable[T : Show](name: String, traversable: Traversable[T]) = {
      if(traversable.isEmpty) None
      else Some(s"$name=${traversable.map(_.show).mkString(",")}")
    }
    private def showOption[T : Show](name: String, option: Option[T]) = {
      option.map(v => s"$name=${v.show}")
    }
  }
}

object refined {
  implicit val finiteDurationValidate: Validate[FiniteDuration, Greater[Nat._0]] = Validate.fromPredicate(
    (d: FiniteDuration) => d.length > 0,
    (d: FiniteDuration) => s"$d is positive",
    Greater(shapeless.nat._0)
  )
}

object headerValues {
  implicit def nonEmptyListHeaderValue[T : ToHeaderValue]: ToHeaderValue[NonEmptyList[T]] = ToHeaderValue { list =>
    implicit val nesShow: Show[NonEmptyString] = Show.show(_.value)
    val tToHeaderValue = implicitly[ToHeaderValue[T]]
    NonEmptyString.unsafeFrom(list.map(tToHeaderValue.toRawValue).mkString_(","))
  }
  implicit val userIdHeaderValue: ToHeaderValue[User.Id] = ToHeaderValue(_.value)
  implicit val indexNameHeaderValue: ToHeaderValue[IndexName] = ToHeaderValue(_.value)
  implicit val transientFilterHeaderValue: ToHeaderValue[Filter] = ToHeaderValue { filter =>
    NonEmptyString.unsafeFrom(FilterTransient.createFromFilter(filter.value.value).serialize())
  }
  implicit val groupHeaderValue: ToHeaderValue[Group] = ToHeaderValue(_.value)
}