package com.bugsee.android.gradle.upload

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins [BundleUploader.redactBodyForLog]: response bodies of failed uploads must never echo credentials. */
class RedactBodyForLogTest {

    @Test fun `null and blank bodies render empty`() {
        assertEquals("", BundleUploader.redactBodyForLog(null))
        assertEquals("", BundleUploader.redactBodyForLog("  "))
    }

    @Test fun `S3 XML body is reduced to length and error code`() {
        val xml = "<Error><Code>SignatureDoesNotMatch</Code><StringToSign>SECRET</StringToSign></Error>"
        val out = BundleUploader.redactBodyForLog(xml)
        assertEquals("<non-JSON body, ${xml.length} chars, code=SignatureDoesNotMatch>", out)
    }

    @Test fun `non-JSON body without a code keeps only its length`() {
        assertEquals("<non-JSON body, 14 chars>", BundleUploader.redactBodyForLog("<html>x</html>"))
    }

    @Test fun `JSON keeps structure but redacts credential-looking keys, nested and in arrays`() {
        val out = BundleUploader.redactBodyForLog(
            """{"error":"nope","Signature":"S1","nested":{"x-amz-credential":"S2","ok":1},"list":[{"secret_key":"S3"}]}""",
        )
        assertTrue(out.contains("\"error\":\"nope\"") && out.contains("\"ok\":1"), out)
        assertFalse(out.contains("S1") || out.contains("S2") || out.contains("S3"), out)
    }

    @Test fun `query strings and the app token are scrubbed from string values`() {
        val token = "abcd1234efgh5678"
        val out = BundleUploader.redactBodyForLog(
            """{"m":"PUT https://b.s3.amazonaws.com/k?X-Amz-Signature=SIG&Expires=1 failed for $token. Why? ok"}""",
            token,
        )
        assertFalse(out.contains("SIG") || out.contains(token), out)
        assertTrue(out.contains("?<redacted>") && out.contains("Why? ok"), out)
    }

    @Test fun `output is capped`() {
        val out = BundleUploader.redactBodyForLog("""{"m":"${"a".repeat(2000)}"}""")
        assertTrue(out.length <= 501, "length ${out.length}")
    }

    @Test fun `S3 style JSON keys are redacted`() {
        val out = BundleUploader.redactBodyForLog(
            """{"AWSAccessKeyId":"AKIA1","StringToSign":"STS1","CanonicalRequest":"CR1","Policy":"P1","Code":"SignatureDoesNotMatch"}""",
        )
        assertFalse(out.contains("AKIA1") || out.contains("STS1") || out.contains("CR1") || out.contains("P1\""), out)
        assertTrue(out.contains("SignatureDoesNotMatch"), out)
    }

    @Test fun `scrubForLog drops queries, SigV4 pairs and masks the token`() {
        val token = "abcd1234efgh5678"
        val out = BundleUploader.scrubForLog(
            "bad URL https://h/k?X-Amz-Signature=S1 token $token Authorization: AWS4 Credential=AKIA2/x, Signature=S3",
            token,
        )
        assertFalse(out.contains("S1") || out.contains("S3") || out.contains("AKIA2") || out.contains(token), out)
    }
}
