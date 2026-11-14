package app.listwerk.core

import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Reply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

class MockTransport(var handler: (HttpRequest) -> Reply) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        val r = handler(request)
        return HttpResponse(r.status, r.headers, r.body.toByteArray(Charsets.UTF_8))
    }

    val last: HttpRequest get() = requests.last()
    fun body(i: Int = -1): String = (if (i < 0) requests[requests.size + i] else requests[i]).bodyText ?: ""
}

private fun ok(body: String, status: Int = 207, headers: Map<String, String> = emptyMap()) = Reply(status, headers, body)

class DavTest {
    private val server = "https://cloud.example.org"
    private fun client(t: MockTransport, base: String = server) = CalDAVClient(base, "nutzer", "geheim-app-pw", t)

    // Nachgebaut nach dem Antwortformat von Nextcloud (sabre/dav); gegen eine echte Instanz noch nicht geprüft.
    private val collectionsXML = """
    <?xml version="1.0"?>
    <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav"
      xmlns:cs="http://calendarserver.org/ns/" xmlns:oc="http://owncloud.org/ns" xmlns:x1="http://apple.com/ns/ical/">
     <d:response><d:href>/remote.php/dav/calendars/nutzer/</d:href>
      <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Arbeit</d:displayname>
        <x1:calendar-color>#1E66F5FF</x1:calendar-color><x1:calendar-order>3</x1:calendar-order>
        <cs:getctag>http://sabre.io/ns/sync/42</cs:getctag><d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>
        <cal:supported-calendar-component-set><cal:comp name="VTODO"/></cal:supported-calendar-component-set>
        <d:current-user-privilege-set><d:privilege><d:all/></d:privilege></d:current-user-privilege-set>
        <d:owner><d:href>/remote.php/dav/principals/users/nutzer/</d:href></d:owner>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/persoenlich/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Termine</d:displayname>
        <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/team%20a_shared_by_anna/</d:href>
      <d:propstat><d:prop>
        <d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><d:displayname>Team A</d:displayname>
        <cal:supported-calendar-component-set><cal:comp name="VTODO"/><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
        <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>
        <d:owner><d:href>/remote.php/dav/principals/users/anna/</d:href></d:owner>
      </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
     <d:response><d:href>/remote.php/dav/calendars/nutzer/trashbin/</d:href>
      <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
    </d:multistatus>
    """.trimIndent()

    @Test
    fun listCollectionsFiltersAndReadsProperties() = runBlocking {
        val t = MockTransport { ok(collectionsXML) }
        val lists = client(t).listCollections("/remote.php/dav/calendars/nutzer/")
        assertEquals(listOf("Arbeit", "Team A"), lists.map { it.name }, "nur Listen mit VTODO")
        val s = lists[0]
        assertEquals("/remote.php/dav/calendars/nutzer/arbeit/", s.href)
        assertEquals("#1e66f5", s.color)
        assertEquals(3.0, s.order)
        assertEquals("http://sabre.io/ns/sync/42", s.syncToken)
        assertTrue(s.canWrite)
        assertFalse(s.isShared)
        assertFalse(lists[1].canWrite, "nur lesen")
        assertTrue(lists[1].isShared)
        assertEquals("anna", lists[1].ownerName)
        assertEquals("PROPFIND", t.last.method)
        assertEquals("1", t.last.header("Depth"))
    }

    @Test
    fun authHeaderAndUserAgent() = runBlocking {
        val t = MockTransport { ok(collectionsXML) }
        client(t).listCollections("/remote.php/dav/calendars/nutzer/")
        val expected = "Basic " + Base64.getEncoder().encodeToString("nutzer:geheim-app-pw".toByteArray())
        assertEquals(expected, t.last.header("Authorization"))
        assertTrue(t.last.header("User-Agent")!!.startsWith("Listwerk/"))
        assertTrue(t.last.url.startsWith("https://"))
    }

    @Test
    fun discover() = runBlocking {
        val t = MockTransport { req ->
            if (req.url.endsWith("/remote.php/dav/")) {
                ok("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/remote.php/dav/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/nutzer/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            } else {
                ok("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/remote.php/dav/principals/users/nutzer/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>https://cloud.example.org/remote.php/dav/calendars/nutzer/</d:href></c:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            }
        }
        val r = client(t).discover()
        assertEquals("/remote.php/dav/principals/users/nutzer/", r.principal)
        assertEquals("/remote.php/dav/calendars/nutzer/", r.home, "absolute URL wird auf den Pfad gekürzt")
    }

    @Test
    fun subdirectoryInstallation() = runBlocking {
        val t = MockTransport { ok("<d:multistatus xmlns:d=\"DAV:\"/>") }
        runCatching { client(t, "https://example.org/nextcloud").discover() }
        assertEquals("https://example.org/nextcloud/remote.php/dav/", t.requests[0].url)
    }

    @Test
    fun syncCollectionParsesChangedAndRemoved() = runBlocking {
        val xml = """
        <?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href><d:propstat><d:prop><d:getetag>"aaa"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/b.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
        <d:sync-token>http://sabre.io/ns/sync/43</d:sync-token></d:multistatus>
        """.trimIndent()
        val t = MockTransport { ok(xml) }
        val ch = client(t).changes("/remote.php/dav/calendars/nutzer/arbeit/", "http://sabre.io/ns/sync/42")
        assertEquals(listOf(RemoteEntry("/remote.php/dav/calendars/nutzer/arbeit/a.ics", "\"aaa\"")), ch.changed)
        assertEquals(listOf("/remote.php/dav/calendars/nutzer/arbeit/b.ics"), ch.removed)
        assertEquals("http://sabre.io/ns/sync/43", ch.newToken)
        assertTrue(t.body().contains("<d:sync-token>http://sabre.io/ns/sync/42</d:sync-token>"))
        assertEquals("REPORT", t.last.method)
    }

    @Test
    fun syncCollectionInvalidToken() = runBlocking {
        val t = MockTransport { Reply(403, body = "<d:error xmlns:d=\"DAV:\"><d:valid-sync-token/></d:error>") }
        assertTrue(client(t).changes("/x/", "alt").tokenInvalid)
    }

    @Test
    fun fetchParsesCalendarDataWithEntities() = runBlocking {
        val xml = """
        <?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
        <d:response><d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag>
        <cal:calendar-data>BEGIN:VCALENDAR&#13;
        VERSION:2.0&#13;
        BEGIN:VTODO&#13;
        UID:a&#13;
        SUMMARY:Fragen &amp; Antworten&#13;
        END:VTODO&#13;
        END:VCALENDAR&#13;
        </cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent()
        val t = MockTransport { ok(xml) }
        val res = client(t).fetch("/remote.php/dav/calendars/nutzer/arbeit/", listOf("/remote.php/dav/calendars/nutzer/arbeit/a.ics"))
        assertEquals(1, res.size)
        assertEquals("\"e1\"", res[0].etag)
        val item = doc(res[0].ics).makeItem()
        assertEquals("a", item.uid)
        assertEquals("Fragen & Antworten", item.title)
        assertTrue(t.body().contains("<d:href>/remote.php/dav/calendars/nutzer/arbeit/a.ics</d:href>"))
    }

    @Test
    fun fetchBatchesRequests() = runBlocking {
        val t = MockTransport { ok("<d:multistatus xmlns:d=\"DAV:\"/>") }
        client(t).fetch("/l/", (0 until 120).map { "/l/$it.ics" })
        assertEquals(3, t.requests.size, "50 + 50 + 20")
    }

    @Test
    fun putHeadersAndResults() = runBlocking {
        val t = MockTransport { req ->
            if (req.header("If-Match") == "\"alt\"") Reply(412) else Reply(201, mapOf("ETag" to "\"neu\""))
        }
        val c = client(t)
        assertEquals(PutResult.Ok("\"neu\""), c.put("/l/a.ics", "X", null, true))
        assertEquals("*", t.last.header("If-None-Match"))
        assertNull(t.last.header("If-Match"))
        assertTrue(t.last.header("Content-Type")!!.startsWith("text/calendar"))

        assertEquals(PutResult.PreconditionFailed, c.put("/l/a.ics", "X", "\"alt\"", false))
        assertEquals("\"alt\"", t.last.header("If-Match"))
    }

    @Test
    fun errorsMapToDavError() = runBlocking {
        val c401 = client(MockTransport { Reply(401) })
        assertFailsWith<DavError.Unauthorized> { c401.listAll("/l/") }
        val c507 = client(MockTransport { Reply(507, body = "Speicher voll") })
        val e = assertFailsWith<DavError.Http> { c507.put("/l/a.ics", "X", null, true) }
        assertEquals(507, e.status)
        assertEquals("Speicher voll", e.detail)
        Unit
    }

    @Test
    fun deleteAndMove() = runBlocking {
        val t = MockTransport { req ->
            when (req.method) {
                "DELETE" -> Reply(if (req.header("If-Match") == "\"x\"") 204 else 412)
                else -> Reply(201)
            }
        }
        val c = client(t)
        assertEquals(DeleteResult.OK, c.delete("/l/a.ics", "\"x\""))
        assertEquals(DeleteResult.PRECONDITION_FAILED, c.delete("/l/a.ics", "\"y\""))
        assertEquals(MoveResult.Ok, c.move("/l/a.ics", "/m/a.ics"))
        assertEquals("https://cloud.example.org/m/a.ics", t.last.header("Destination"))
        assertEquals("F", t.last.header("Overwrite"))
    }

    @Test
    fun makeAndUpdateCollectionEscapeNames() = runBlocking {
        val t = MockTransport { Reply(201) }
        client(t).makeCollection("/remote.php/dav/calendars/nutzer/x/", "Eltern & <Kinder>", "#ff0000")
        assertEquals("MKCALENDAR", t.last.method)
        assertTrue(t.body().contains("Eltern &amp; &lt;Kinder&gt;"))
        assertTrue(t.body().contains("<c:comp name=\"VTODO\"/>"))
        t.handler = { Reply(207) }
        client(t).updateCollection("/remote.php/dav/calendars/nutzer/x/", "Neu", "#00ff00")
        assertEquals("PROPPATCH", t.last.method)
        assertTrue(t.body().contains("<d:displayname>Neu</d:displayname>"))
    }

    // MARK: Login Flow v2

    @Test
    fun loginFlowV2() = runBlocking {
        var polls = 0
        val t = MockTransport { req ->
            if (req.url.endsWith("/login/v2")) {
                Reply(200, body = """{"poll":{"token":"tok123","endpoint":"https://cloud.example.org/index.php/login/v2/poll"},"login":"https://cloud.example.org/index.php/login/v2/flow/abc"}""")
            } else {
                polls++
                if (polls < 3) Reply(404)
                else Reply(200, body = """{"server":"https://cloud.example.org","loginName":"nutzer","appPassword":"app-pw-xyz"}""")
            }
        }
        val flow = LoginFlowV2(t)
        val session = flow.start("https://cloud.example.org/")
        assertEquals("https://cloud.example.org/index.php/login/v2/flow/abc", session.loginUrl)
        assertEquals("https://cloud.example.org/index.php/login/v2", t.requests[0].url)
        val creds = flow.waitForCredentials(session, intervalMs = 10, timeoutMs = 5000)
        assertEquals(NextcloudCredentials("https://cloud.example.org", "nutzer", "app-pw-xyz"), creds)
        assertEquals(3, polls)
        assertEquals("token=tok123", t.body())
    }
}
