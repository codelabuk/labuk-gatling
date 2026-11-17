package org.codelabuk.perf.runner

import java.time.ZonedDateTime

import org.scalatest.funsuite.AnyFunSuite

class S3Spec extends AnyFunSuite {

  test("Signature V4 matches AWS's published example (S3 docs: 'Example: GET Object')") {
    val auth = S3.authorization(
      method = "GET",
      path = "/test.txt",
      query = "",
      headers = Map(
        "host" -> "examplebucket.s3.amazonaws.com",
        "range" -> "bytes=0-9",
        "x-amz-content-sha256" -> "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "x-amz-date" -> "20130524T000000Z"),
      payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      accessKey = "AKIAIOSFODNN7EXAMPLE",
      secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
      region = "us-east-1",
      time = ZonedDateTime.parse("2013-05-24T00:00:00Z"))
    assert(auth == "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
      "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
      "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")
  }

  test("URLs and encoding") {
    assert(S3.parse("s3://perf-results/runs/orders-1/logs") == ("perf-results", "runs/orders-1/logs"))
    assert(S3.parse("s3://perf-results") == ("perf-results", ""))
    assert(S3.encodePath("runs/a b/report+1.html") == "runs/a%20b/report%2B1.html")
    assert(S3.encode("runs/") == "runs%2F")
    intercept[IllegalStateException](S3.fromEnv(_ => None))
  }
}
