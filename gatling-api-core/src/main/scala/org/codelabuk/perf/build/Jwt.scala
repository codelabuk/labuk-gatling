package org.codelabuk.perf.build

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.Try

import com.fasterxml.jackson.databind.ObjectMapper

/** JWT helpers with no third-party JWT library: read `exp`, and mint HMAC-signed test tokens. */
object Jwt {
  private val json = new ObjectMapper()
  private val urlEncoder = Base64.getUrlEncoder.withoutPadding()
  private val urlDecoder = Base64.getUrlDecoder

  /** Expiry of a JWT in epoch millis, if the token is a JWT with an `exp` claim. */
  def expiresAtMillis(token: String): Option[Long] =
    token.split('.') match {
      case Array(_, payload, _*) =>
        Try(json.readTree(urlDecoder.decode(payload))).toOption
          .flatMap(n => Option(n.get("exp")))
          .filter(_.canConvertToLong)
          .map(_.asLong() * 1000)
      case _ => None
    }

  private val JavaAlg = Map("HS256" -> "HmacSHA256", "HS384" -> "HmacSHA384", "HS512" -> "HmacSHA512")

  /** Builds and signs a JWT. Claim values that look numeric/boolean are written as such. */
  def sign(algorithm: String, secret: Array[Byte], claims: Map[String, Any]): String = {
    val header = json.createObjectNode().put("alg", algorithm).put("typ", "JWT")
    val body = json.createObjectNode()
    claims.foreach {
      case (k, v: Long)    => body.put(k, v)
      case (k, v: Int)     => body.put(k, v)
      case (k, v: Boolean) => body.put(k, v)
      case (k, v: String) if v.matches("-?\\d{1,18}") => body.put(k, v.toLong)
      case (k, v: String) if v == "true" || v == "false" => body.put(k, v.toBoolean)
      case (k, v)          => body.put(k, String.valueOf(v))
    }
    val signingInput =
      urlEncoder.encodeToString(json.writeValueAsBytes(header)) + "." + urlEncoder.encodeToString(json.writeValueAsBytes(body))
    val mac = Mac.getInstance(JavaAlg(algorithm))
    mac.init(new SecretKeySpec(secret, JavaAlg(algorithm)))
    signingInput + "." + urlEncoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)))
  }
}
