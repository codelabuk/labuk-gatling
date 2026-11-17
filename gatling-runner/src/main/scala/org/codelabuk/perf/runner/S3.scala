package org.codelabuk.perf.runner

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * A small S3 client (PUT / GET / LIST) with AWS Signature Version 4 - enough to keep run results in S3 or MinIO
 * without the AWS SDK. Configuration (standard AWS variable names):
 *   AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, [AWS_SESSION_TOKEN], AWS_REGION (default us-east-1)
 *   S3_ENDPOINT   e.g. http://minio.perf:9000 - path-style requests (MinIO); unset = AWS virtual-hosted style
 * Not supported: EKS IRSA / web-identity credentials (use access keys, e.g. from a Secret).
 */
final class S3(accessKey: String, secretKey: String, sessionToken: Option[String], region: String, endpoint: Option[String]) {

  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
  private val EmptyHash = S3.hex(S3.sha256(Array.emptyByteArray))

  private def url(bucket: String, key: String, query: String): (URI, String, String) = {
    val path = "/" + S3.encodePath(key)
    endpoint match {
      case Some(e) =>
        val base = URI.create(e.stripSuffix("/"))
        val p = s"/$bucket$path"
        (URI.create(s"${base.getScheme}://${base.getRawAuthority}$p${if (query.isEmpty) "" else "?" + query}"), base.getRawAuthority, p)
      case None =>
        val host = s"$bucket.s3.$region.amazonaws.com"
        (URI.create(s"https://$host$path${if (query.isEmpty) "" else "?" + query}"), host, path)
    }
  }

  private def send(method: String, bucket: String, key: String, query: String, body: Array[Byte]): HttpResponse[Array[Byte]] = {
    val (uri, host, path) = url(bucket, key, query)
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val payloadHash = if (body.isEmpty) EmptyHash else S3.hex(S3.sha256(body))
    val headers = Map("host" -> host, "x-amz-content-sha256" -> payloadHash, "x-amz-date" -> S3.amzDate(now)) ++
      sessionToken.map("x-amz-security-token" -> _)
    val auth = S3.authorization(method, path, query, headers, payloadHash, accessKey, secretKey, region, now)
    val b = HttpRequest.newBuilder(uri).method(method, if (body.isEmpty) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
    headers.foreach { case (k, v) => if (k != "host") b.header(k, v) }
    b.header("Authorization", auth)
    http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
  }

  private def check(r: HttpResponse[Array[Byte]], what: String): Unit =
    if (r.statusCode() / 100 != 2) {
      val body = new String(r.body(), StandardCharsets.UTF_8)
      val code = "<Code>([^<]+)</Code>".r.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
      throw new IllegalStateException(s"S3 $what failed: HTTP ${r.statusCode()} $code".trim)
    }

  def put(bucket: String, key: String, data: Array[Byte]): Unit = check(send("PUT", bucket, key, "", data), s"upload of s3://$bucket/$key")

  def get(bucket: String, key: String): Array[Byte] = {
    val r = send("GET", bucket, key, "", Array.emptyByteArray)
    check(r, s"download of s3://$bucket/$key")
    r.body()
  }

  /** All keys under a prefix (ListObjectsV2, follows continuation tokens). */
  def list(bucket: String, prefix: String): List[String] = {
    val keys = ListBuffer.empty[String]
    var token: Option[String] = None
    var more = true
    while (more) {
      val params = (List("list-type" -> "2", "prefix" -> prefix) ++ token.map("continuation-token" -> _)).sortBy(_._1)
      val query = params.map { case (k, v) => s"${S3.encode(k)}=${S3.encode(v)}" }.mkString("&")
      val r = send("GET", bucket, "", query, Array.emptyByteArray)
      check(r, s"listing of s3://$bucket/$prefix")
      val xml = new String(r.body(), StandardCharsets.UTF_8)
      keys ++= "<Key>([^<]+)</Key>".r.findAllMatchIn(xml).map(m => S3.unescapeXml(m.group(1)))
      token = "<NextContinuationToken>([^<]+)</NextContinuationToken>".r.findFirstMatchIn(xml).map(m => S3.unescapeXml(m.group(1)))
      more = xml.contains("<IsTruncated>true</IsTruncated>") && token.isDefined
    }
    keys.toList
  }
}

object S3 {

  /** s3://bucket/key/prefix -> (bucket, key) */
  def parse(url: String): (String, String) = {
    require(isS3(url), s"not an s3:// URL: $url")
    url.stripPrefix("s3://").split("/", 2) match {
      case Array(b, k) => (b, k.stripSuffix("/"))
      case Array(b)    => (b, "")
    }
  }

  def isS3(path: String): Boolean = path.startsWith("s3://")

  /** Client from the standard environment variables. */
  def fromEnv(env: String => Option[String] = sys.env.get): S3 = {
    def need(n: String) = env(n).filter(_.nonEmpty).getOrElse(throw new IllegalStateException(s"s3:// results need $n to be set"))
    new S3(need("AWS_ACCESS_KEY_ID"), need("AWS_SECRET_ACCESS_KEY"), env("AWS_SESSION_TOKEN").filter(_.nonEmpty),
      env("AWS_REGION").orElse(env("AWS_DEFAULT_REGION")).filter(_.nonEmpty).getOrElse("us-east-1"), env("S3_ENDPOINT").filter(_.nonEmpty))
  }

  // ---------------------------------------------------------------- local files <-> s3

  def upload(s3: S3, file: Path, url: String): Unit = { val (b, k) = parse(url); s3.put(b, k, Files.readAllBytes(file)) }

  def uploadDir(s3: S3, dir: Path, url: String): Int = {
    val (b, prefix) = parse(url)
    val files = Files.walk(dir).iterator().asScala.filter(Files.isRegularFile(_)).toList
    files.foreach(f => s3.put(b, (prefix + "/" + dir.relativize(f).toString.replace('\\', '/')).stripPrefix("/"), Files.readAllBytes(f)))
    files.size
  }

  def download(s3: S3, url: String, file: Path): Path = {
    val (b, k) = parse(url)
    Option(file.getParent).foreach(Files.createDirectories(_))
    Files.write(file, s3.get(b, k))
  }

  /** Downloads every object under the prefix whose name ends with `suffix`; returns how many. */
  def downloadAll(s3: S3, url: String, dir: Path, suffix: String): Int = {
    val (b, prefix) = parse(url)
    val keys = s3.list(b, if (prefix.isEmpty) "" else prefix + "/").filter(_.endsWith(suffix))
    keys.foreach(k => download(s3, s"s3://$b/$k", dir.resolve(k.split('/').last)))
    keys.size
  }

  // ---------------------------------------------------------------- Signature V4

  private val Unreserved = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "-_.~").toSet

  def encode(s: String): String =
    s.getBytes(StandardCharsets.UTF_8).map { b =>
      val c = (b & 0xff).toChar
      if (Unreserved(c)) c.toString else "%%%02X".format(b & 0xff)
    }.mkString

  def encodePath(key: String): String = key.split("/", -1).map(encode).mkString("/")

  def amzDate(t: ZonedDateTime): String = t.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

  def sha256(b: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(b)
  def hex(b: Array[Byte]): String = b.map("%02x".format(_)).mkString
  private def hmac(key: Array[Byte], data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
  }

  /** The Authorization header for a request (headers: lower-case names, all of them are signed). */
  def authorization(method: String, path: String, query: String, headers: Map[String, String], payloadHash: String,
                    accessKey: String, secretKey: String, region: String, time: ZonedDateTime): String = {
    val sorted = headers.toList.map { case (k, v) => k.toLowerCase -> v.trim }.sortBy(_._1)
    val signedHeaders = sorted.map(_._1).mkString(";")
    val canonical = List(method, path, query, sorted.map { case (k, v) => s"$k:$v\n" }.mkString, signedHeaders, payloadHash).mkString("\n")
    val day = time.format(DateTimeFormatter.BASIC_ISO_DATE).take(8)
    val scope = s"$day/$region/s3/aws4_request"
    val toSign = List("AWS4-HMAC-SHA256", amzDate(time), scope, hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)))).mkString("\n")
    val key = List(day, region, "s3", "aws4_request").foldLeft(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8))(hmac)
    s"AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=${hex(hmac(key, toSign))}"
  }

  private def unescapeXml(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
