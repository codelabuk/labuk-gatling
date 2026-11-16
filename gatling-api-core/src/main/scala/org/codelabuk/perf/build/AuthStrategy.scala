package org.codelabuk.perf.build

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.concurrent.duration._

import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.session.el._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.request.builder.HttpRequestBuilder
import org.codelabuk.perf.model.{AuthDef, ExtractDef}

/**
 * How a virtual user gets and presents its credentials.
 *  - setup:   runs once per virtual user before its first step
 *  - refresh: runs before each authenticated request; renews the token shortly before it expires
 *  - decorate: adds the token to a request (header + prefix)
 */
trait AuthStrategy {
  def setup: ChainBuilder
  def refresh: Option[ChainBuilder]
  def decorate(req: HttpRequestBuilder): HttpRequestBuilder
  /** Requests that must not carry the token (the login call itself). */
  def skips(apiName: String): Boolean = false
}

object AuthStrategy {

  private def expiresKey(tokenVar: String) = s"__auth_${tokenVar}_expiresAt"

  private def headerDecorator(header: String, prefix: String, tokenVar: String): HttpRequestBuilder => HttpRequestBuilder = {
    val value: Expression[String] = (prefix.replace("#{", "\\#{") + s"#{$tokenVar}").el[String]
    _.header(header, value)
  }

  private def needsRefresh(tokenVar: String, before: FiniteDuration): Expression[Boolean] = session =>
    session(expiresKey(tokenVar)).asOption[Long].exists(exp => System.currentTimeMillis() >= exp - before.toMillis).success

  /**
   * @param apiCall builds an un-authenticated call to a catalog API with extra extractions
   *                (supplied by the chain builder, so login reuses the normal API definition).
   */
  def of(defn: AuthDef, apiCall: (String, List[ExtractDef]) => ChainBuilder): AuthStrategy = defn match {

    case d: AuthDef.Login =>
      new AuthStrategy {
        private val key = expiresKey(d.tokenVar)
        private val doLogin: ChainBuilder =
          exec(_.remove(d.tokenVar))
            .exec(apiCall(d.api, List(ExtractDef(d.tokenVar, "jsonPath", d.tokenPath, all = false, "string", optional = false))))
            .exec { session =>
              session(d.tokenVar).asOption[String] match {
                case Some(token) =>
                  val exp = Jwt.expiresAtMillis(token).orElse(d.ttl.map(t => System.currentTimeMillis() + t.toMillis))
                  exp.fold(session.remove(key))(e => session.set(key, e))
                case None => session
              }
            }
            // no token -> this virtual user cannot continue; the failed login is already in the report
            .exitHereIf(s => (!s.contains(d.tokenVar)).success)

        val setup: ChainBuilder = doLogin
        val refresh: Option[ChainBuilder] = Some(doIf(needsRefresh(d.tokenVar, d.refreshBefore.getOrElse(30.seconds)))(doLogin))
        private val deco = headerDecorator(d.header, d.prefix, d.tokenVar)
        def decorate(req: HttpRequestBuilder): HttpRequestBuilder = deco(req)
        override def skips(apiName: String): Boolean = apiName == d.api
      }

    case d: AuthDef.Jwt =>
      new AuthStrategy {
        private val key = expiresKey(d.tokenVar)
        private val secret =
          if (d.secretBase64) Base64.getDecoder.decode(d.secret.trim) else d.secret.getBytes(StandardCharsets.UTF_8)
        private val claimExprs: Map[String, Expression[Any]] = d.claims.map { case (k, v) => k -> v.el[Any] }

        private val mint: ChainBuilder = exec { session =>
          val now = System.currentTimeMillis()
          val exp = now + d.validity.toMillis
          val resolved = claimExprs.toList.foldLeft(Map[String, Any]("iat" -> now / 1000, "exp" -> exp / 1000).success) {
            case (acc, (k, e)) => acc.flatMap(m => e(session).map(v => m + (k -> v)))
          }
          resolved.map(claims => session.set(d.tokenVar, Jwt.sign(d.algorithm, secret, claims)).set(key, exp))
        }

        val setup: ChainBuilder = mint
        val refresh: Option[ChainBuilder] = Some(doIf(needsRefresh(d.tokenVar, d.refreshBefore.getOrElse(30.seconds)))(mint))
        private val deco = headerDecorator(d.header, d.prefix, d.tokenVar)
        def decorate(req: HttpRequestBuilder): HttpRequestBuilder = deco(req)
      }

    case d: AuthDef.Static =>
      new AuthStrategy {
        private val value = d.token.el[String]
        val setup: ChainBuilder = exec(session => value(session).map(v => session.set(d.tokenVar, v)))
        val refresh: Option[ChainBuilder] = None
        private val deco = headerDecorator(d.header, d.prefix, d.tokenVar)
        def decorate(req: HttpRequestBuilder): HttpRequestBuilder = deco(req)
      }
  }
}
