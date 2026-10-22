package com.legitimateapps.dulcet.server.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Connect to the address already checked by redirect policy, retaining TLS hostname verification. */
internal object PinnedImageHttp {
    private val deadlines=Timer("dulcet-image-deadlines",true)
    suspend fun get(uri:URI,address:InetAddress,maxBytes:Int,userAgent:String):UpstreamResponse = withContext(Dispatchers.IO) {
        val socket=Socket()
        // Cancellation closes the blocking socket promptly; the IO worker owns normal cleanup.
        val guard=CoroutineScope(coroutineContext).launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { socket.close() }
        }
        val deadline=object:TimerTask(){override fun run(){runCatching { socket.close() }}}
        deadlines.schedule(deadline,10_000)
        try {
            socket.use {
                val port=if(uri.port>=0) uri.port else if(uri.scheme=="https") 443 else 80
                socket.connect(InetSocketAddress(address,port),5000);socket.soTimeout=10_000
                val wire=if(uri.scheme=="https") (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,uri.host,port,true) else socket
                wire.use {
                    if(wire is SSLSocket) { wire.sslParameters=wire.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" };wire.startHandshake() }
                    val host=if(uri.host.contains(':')) "[${uri.host}]" else uri.host
                    val target=uri.rawPath.ifEmpty { "/" }+(uri.rawQuery?.let { "?$it" } ?: "")
                    val request="GET $target HTTP/1.1\r\nHost: $host${if(uri.port>=0) ":$port" else ""}\r\nUser-Agent: $userAgent\r\nAccept: image/*\r\nConnection: close\r\n\r\n"
                    wire.getOutputStream().write(request.toByteArray(Charsets.US_ASCII));wire.getOutputStream().flush()
                    val input=BufferedInputStream(wire.getInputStream())
                    var headerBytes=0
                    fun line():String {
                        val out=ByteArrayOutputStream()
                        while(true) {
                            val b=input.read(); if(b<0 || ++headerBytes>65536) throw UpstreamUnavailable()
                            if(b==10) return out.toString(Charsets.US_ASCII).removeSuffix("\r")
                            out.write(b)
                        }
                    }
                    val status=line().split(' ').getOrNull(1)?.toIntOrNull() ?: throw UpstreamUnavailable()
                    if(status !in 200..599) throw UpstreamUnavailable()
                    val headers=linkedMapOf<String,MutableList<String>>()
                    while(true) { val line=line();if(line.isEmpty())break;val pos=line.indexOf(':');if(pos<1)throw UpstreamUnavailable(); headers.getOrPut(line.take(pos).lowercase()){mutableListOf()}+=line.substring(pos+1).trim() }
                    if(headers["content-encoding"]?.any { it!="identity" }==true) throw UpstreamUnavailable()
                    val out=ByteArrayOutputStream()
                    fun copy(size:Int) {
                        if(size<0 || out.size().toLong()+size>maxBytes) throw UpstreamUnavailable()
                        val bytes=input.readNBytes(size); if(bytes.size!=size)throw UpstreamUnavailable();out.write(bytes)
                    }
                    val transfer=headers["transfer-encoding"]?.joinToString(",")?.lowercase()
                    val lengths=headers["content-length"].orEmpty().distinct()
                    if(lengths.size>1 || (transfer!=null && lengths.isNotEmpty())) throw UpstreamUnavailable()
                    if(transfer!=null) {
                        if(transfer!="chunked")throw UpstreamUnavailable()
                        while(true) {
                            val size=line().substringBefore(';').toIntOrNull(16) ?: throw UpstreamUnavailable()
                            if(size==0) { while(line().isNotEmpty()){};break }
                            copy(size);if(line().isNotEmpty())throw UpstreamUnavailable()
                        }
                    } else if(lengths.isNotEmpty()) copy(lengths.single().toIntOrNull() ?: throw UpstreamUnavailable())
                    else { val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>maxBytes)throw UpstreamUnavailable();out.write(buffer,0,n)} }
                    UpstreamResponse(status,headers,out.toByteArray())
                }
            }
        } finally { deadline.cancel();socket.close();guard.cancel() }
    }
}
