package org.codelabuk.perf.it

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentHashMap, Executors, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicInteger

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.codelabuk.perf.build.Jwt

/**
 * Test-only stand-in for the real APIs:
 *  POST /auth/login             -> { token } (HS256 JWT, 6s lifetime so refresh gets exercised)
 *  POST /sr/load-filter-data    -> { items: [ { srId, region } ... ] }
 *  GET  /sr/{id}/status         -> { srId, email, status } - PENDING for 2-5s after first look (SR-0030: forever)
 *  GET  /sr/{id}/wait-time      -> { waitSeconds } (1-2)
 * Every /sr call needs a valid, unexpired, correctly signed Bearer token.
 */
object MockApiServer {
  val Secret = "test-secret"
  val TokenTtlSeconds = 6
  val SrCount = 30
  /** One SR stays PENDING forever, so the demo shows how a poll that gives up is reported. */
  val StuckSr = "SR-0030"

  private val json = new ObjectMapper()
  private val readyAt = new ConcurrentHashMap[String, java.lang.Long]()
  val logins = new AtomicInteger
  val rejected = new AtomicInteger
  @volatile private var server: HttpServer = _

  def start(port: Int, host: String = "localhost"): Unit = synchronized {
    if (server == null) {
      server = HttpServer.create(new InetSocketAddress(host, port), 512)
      server.setExecutor(Executors.newFixedThreadPool(16))
      server.createContext("/", handle _)
      server.start()
      println(s"[mock] API listening on http://$host:$port")
    }
  }

  def stop(): Unit = synchronized {
    if (server != null) {
      server.stop(0); server = null
      println(s"[mock] logins=${logins.get} rejected-unauthorized=${rejected.get} srs-seen=${readyAt.size}")
    }
  }

  private def handle(ex: HttpExchange): Unit =
    try {
      val path = ex.getRequestURI.getPath
      (ex.getRequestMethod, path.split('/').toList.drop(1)) match {
        case ("POST", List("auth", "login")) =>
          val body = json.readTree(ex.getRequestBody)
          if (Option(body.get("password")).map(_.asText).contains("secret")) {
            logins.incrementAndGet()
            val now = System.currentTimeMillis() / 1000
            val token = Jwt.sign("HS256", Secret.getBytes(StandardCharsets.UTF_8),
              Map("sub" -> body.get("username").asText, "iat" -> now, "exp" -> (now + TokenTtlSeconds)))
            reply(ex, 200, s"""{"token":"$token"}""")
          } else reply(ex, 401, """{"error":"bad credentials"}""")

        case (_, "sr" :: _) if !authorized(ex) =>
          rejected.incrementAndGet()
          reply(ex, 401, """{"error":"missing, invalid or expired token"}""")

        case ("POST", List("sr", "load-filter-data")) =>
          val items = (1 to SrCount).map(i => f"""{"srId":"SR-$i%04d","region":"EU"}""").mkString(",")
          reply(ex, 200, s"""{"items":[$items]}""")

        case ("GET", List("sr", id, "status")) =>
          val ready = readyAt.computeIfAbsent(id, _ =>
            if (id == StuckSr) Long.MaxValue // never completes - exercises a poll that gives up
            else System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(2000, 5001))
          val status = if (System.currentTimeMillis() >= ready) "COMPLETED" else "PENDING"
          reply(ex, 200, s"""{"srId":"$id","email":"${id.toLowerCase}@example.com","status":"$status"}""")

        case ("GET", List("sr", id, "wait-time")) =>
          val ready = Option(readyAt.get(id)).map(_.longValue).getOrElse(System.currentTimeMillis())
          val remaining = if (ready == Long.MaxValue) 2L else math.ceil((ready - System.currentTimeMillis()) / 1000.0).toLong
          reply(ex, 200, s"""{"waitSeconds":${math.max(1L, math.min(remaining, 2L))}}""")

        case _ => reply(ex, 404, """{"error":"not found"}""")
      }
    } catch {
      case e: Exception => reply(ex, 500, s"""{"error":"${e.getMessage}"}""")
    }

  private def authorized(ex: HttpExchange): Boolean =
    Option(ex.getRequestHeaders.getFirst("Authorization")).filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer ")).exists { token =>
      token.split('.') match {
        case Array(h, p, sig) =>
          val payload = json.readTree(java.util.Base64.getUrlDecoder.decode(p))
          val notExpired = Option(payload.get("exp")).exists(_.asLong * 1000 > System.currentTimeMillis())
          resign(h, p) == sig && notExpired
        case _ => false
      }
    }

  private def resign(header: String, payload: String): String = {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(Secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(s"$header.$payload".getBytes(StandardCharsets.US_ASCII)))
  }

  private def reply(ex: HttpExchange, code: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.length.toLong)
    ex.getResponseBody.write(bytes)
    ex.close()
  }
}
