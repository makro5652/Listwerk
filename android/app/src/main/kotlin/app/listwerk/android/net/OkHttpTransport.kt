package app.listwerk.android.net

import app.listwerk.core.DavError
import app.listwerk.core.HttpRequest
import app.listwerk.core.HttpResponse
import app.listwerk.core.HttpTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OkHttp-Implementierung des Transports aus :core. Kein Cookie-Speicher, kein Cache; TLS-Prüfung bleibt auf Systemstandard. */
class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResponse {
        val call = client.newCall(toOkHttp(request))
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(DavError.Network(e.message ?: e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            val body = r.body?.bytes() ?: ByteArray(0)
                            val headers = HashMap<String, String>()
                            for (name in r.headers.names()) headers[name.lowercase()] = r.headers[name] ?: ""
                            if (cont.isActive) cont.resume(HttpResponse(r.code, headers, body))
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(DavError.Network(e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
            })
        }
    }

    private fun toOkHttp(req: HttpRequest): Request {
        val builder = Request.Builder().url(req.url)
        var contentType: String? = null
        for ((k, v) in req.headers) {
            if (k.equals("Content-Type", ignoreCase = true)) contentType = v else builder.header(k, v)
        }
        val mustHaveBody = req.method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
        val body: RequestBody? = when {
            req.method == "GET" || req.method == "HEAD" -> null
            req.body != null -> req.body!!.toRequestBody(contentType?.toMediaTypeOrNull())
            mustHaveBody -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> null
        }
        return builder.method(req.method, body).build()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }
}
