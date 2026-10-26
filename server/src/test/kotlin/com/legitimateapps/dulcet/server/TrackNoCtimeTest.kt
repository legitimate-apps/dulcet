package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.scan.Probe
import com.legitimateapps.dulcet.server.scan.Scanner
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import kotlin.test.*

class TrackNoCtimeTest {
    @Test fun noCtimeProviderNeverReadsUnchangedAudioButStillProbesSizeAndMtimeChanges() {
        val root=Files.createTempDirectory("track-no-ctime")
        val ctx=Fixtures.context(root)
        try {
            val file=ctx.config.musicFolder.resolve("generated.flac")
            val picture=root.resolve("picture.jpg")
            Fixtures.jpeg(picture,32)
            Fixtures.track(file,picture=picture)
            val calls=root.resolve("probe-calls")
            val executable=root.resolve("counting-probe")
            fun quote(s:String)="'"+s.replace("'","'\\''")+"'"
            Files.writeString(executable,"#!/bin/sh\nprintf 'call\\n' >> ${quote(calls.toString())}\nexec ${quote(Fixtures.ffprobe)} \"\$@\"\n")
            assertTrue(executable.toFile().setExecutable(true))
            val provider=NoCtimeProvider(file.fileSystem)
            val wrapped=provider.wrap(file)
            assertFalse("unix" in wrapped.fileSystem.supportedFileAttributeViews())
            assertFailsWith<UnsupportedOperationException> { Files.getAttribute(wrapped,"unix:ctime") }
            // Prove that the byte counter sees actual audio reads through this provider.
            Files.newInputStream(wrapped).use { assertEquals(4,it.readNBytes(4).size) }
            assertEquals(4L,provider.audioBytesRead)
            val scanner=Scanner(ctx.db,provider.wrap(ctx.config.musicFolder),Probe(executable.toString()))
            assertEquals(1,scanner.scan()!!.added)
            assertTrue(Files.readAllLines(calls).isNotEmpty(),"The counting executable must actually probe the fixture")
            assertEquals(1L,ctx.db.read { long("SELECT has_embedded_art FROM track") },"The scan must exercise embedded source stamps too")
            fun unchanged() {
                provider.audioBytesRead=0;provider.ctimeRequests=0
                Files.writeString(calls,"")
                val result=scanner.scan()!!
                assertTrue(provider.ctimeRequests>0,"The scanner must actually encounter the unsupported ctime view")
                assertEquals(0L,provider.audioBytesRead,"An unchanged scan must not hash or read any audio content")
                assertEquals(0,Files.readAllLines(calls).size,"An unchanged scan must not invoke the probe")
                assertEquals(1,result.unchanged);assertEquals(0,result.updated);assertEquals(0,result.failed)
            }
            unchanged()
            // File-key-only changes are untrusted without ctime and cannot force a tag reread.
            val oldKey=Files.readAttributes(file,BasicFileAttributes::class.java).fileKey()
            val replacement=file.resolveSibling("replacement.flac")
            Files.copy(file,replacement,StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING)
            assertNotEquals(oldKey,Files.readAttributes(file,BasicFileAttributes::class.java).fileKey())
            unchanged()
            val size=Files.size(file)
            Files.write(file,byteArrayOf(0),StandardOpenOption.APPEND)
            assertEquals(size+1,Files.size(file),"The size-change condition must actually be reached")
            assertEquals(1,scanner.scan()!!.updated)
            assertTrue(Files.readAllLines(calls).isNotEmpty(),"Size changes must invoke the real probe")
            unchanged()
            val mtime=Files.getLastModifiedTime(file)
            Files.setLastModifiedTime(file,FileTime.fromMillis(mtime.toMillis()+2000))
            assertNotEquals(mtime.toMillis(),Files.getLastModifiedTime(file).toMillis(),"The mtime-change condition must actually be reached")
            assertEquals(1,scanner.scan()!!.updated)
            assertTrue(Files.readAllLines(calls).isNotEmpty(),"Mtime changes must invoke the real probe")
            unchanged()
            assertTrue(ctx.db.read { string("SELECT identity FROM track_file_identity_v1")!! }.startsWith("metadata-only:"))
            Files.writeString(calls,"")
            // Moving to a provider with ctime adopts a trusted baseline without probing again.
            assertNotNull(Files.getAttribute(file,"unix:ctime"))
            val trusted=Scanner(ctx.db,ctx.config.musicFolder,Probe(executable.toString())).scan()!!
            assertEquals(1,trusted.unchanged);assertEquals(0,trusted.updated);assertEquals(0,trusted.failed)
            assertEquals(0,Files.readAllLines(calls).size)
            assertFalse(ctx.db.read { string("SELECT identity FROM track_file_identity_v1")!! }.startsWith("metadata-only:"))
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
    @Test fun standaloneArtworkKeepsContentDigestFallbackWithoutCtime() {
        val root=Files.createTempDirectory("art-no-ctime")
        try {
            val file=root.resolve("cover.jpg")
            Fixtures.jpeg(file,32)
            val provider=NoCtimeProvider(file.fileSystem)
            val before=com.legitimateapps.dulcet.server.media.artworkFileIdentity(provider.wrap(file))
            val bytes=Files.readAllBytes(file);val mtime=Files.getLastModifiedTime(file)
            Files.write(file,bytes.copyOf().also { it[20]=(it[20].toInt() xor 1).toByte() })
            Files.setLastModifiedTime(file,mtime)
            assertEquals(bytes.size.toLong(),Files.size(file));assertEquals(mtime,Files.getLastModifiedTime(file))
            assertFalse(bytes.contentEquals(Files.readAllBytes(file)),"Artwork bytes must actually change")
            assertNotEquals(before,com.legitimateapps.dulcet.server.media.artworkFileIdentity(provider.wrap(file)))
            assertEquals(2,provider.ctimeRequests,"Both identities must actually use the unsupported-view fallback")
        } finally { root.toFile().deleteRecursively() }
    }
}

/** Real files behind a provider that rejects unix:ctime and counts actual audio bytes read. */
private class NoCtimeProvider(private val delegate:FileSystem):FileSystemProvider() {
    var audioBytesRead=0L
    var ctimeRequests=0
    private val nio=delegate.provider()
    private val fs=object:FileSystem() {
        override fun provider()=this@NoCtimeProvider
        override fun close()=Unit
        override fun isOpen()=delegate.isOpen
        override fun isReadOnly()=delegate.isReadOnly
        override fun getSeparator()=delegate.separator
        override fun getRootDirectories()=delegate.rootDirectories.map(::wrap)
        override fun getFileStores()=delegate.fileStores
        override fun supportedFileAttributeViews()=delegate.supportedFileAttributeViews()-"unix"
        override fun getPath(first:String,vararg more:String)=wrap(delegate.getPath(first,*more))
        override fun getPathMatcher(syntaxAndPattern:String)=PathMatcher { nioPath -> delegate.getPathMatcher(syntaxAndPattern).matches(unwrap(nioPath)) }
        override fun getUserPrincipalLookupService()=delegate.userPrincipalLookupService
        override fun newWatchService()=delegate.newWatchService()
    }
    fun wrap(path:Path):Path=Wrapped(path)
    private fun unwrap(path:Path)=(path as? Wrapped)?.path ?: path
    private inner class Wrapped(val path:Path):Path by path {
        override fun getFileSystem()=fs
        override fun getRoot()=path.root?.let(::wrap)
        override fun getFileName()=path.fileName?.let(::wrap)
        override fun getParent()=path.parent?.let(::wrap)
        override fun getName(index:Int)=wrap(path.getName(index))
        override fun subpath(beginIndex:Int,endIndex:Int)=wrap(path.subpath(beginIndex,endIndex))
        override fun normalize()=wrap(path.normalize())
        override fun resolve(other:Path)=wrap(path.resolve(unwrap(other)))
        override fun resolve(other:String)=wrap(path.resolve(other))
        override fun resolveSibling(other:Path)=wrap(path.resolveSibling(unwrap(other)))
        override fun resolveSibling(other:String)=wrap(path.resolveSibling(other))
        override fun relativize(other:Path)=wrap(path.relativize(unwrap(other)))
        override fun startsWith(other:Path)=path.startsWith(unwrap(other))
        override fun endsWith(other:Path)=path.endsWith(unwrap(other))
        override fun toAbsolutePath()=wrap(path.toAbsolutePath())
        override fun toRealPath(vararg options:LinkOption)=wrap(path.toRealPath(*options))
        override fun iterator()=path.map(::wrap).toMutableList().iterator()
        override fun compareTo(other:Path)=path.compareTo(unwrap(other))
        override fun equals(other:Any?)=other is NoCtimeProvider.Wrapped && other.fileSystem===fs && path==other.path
        override fun hashCode()=path.hashCode()
        override fun toString()=path.toString()
    }
    override fun getScheme()="no-ctime-test"
    override fun newFileSystem(uri:URI,env:MutableMap<String,*>):FileSystem=throw FileSystemAlreadyExistsException()
    override fun getFileSystem(uri:URI)=fs
    override fun getPath(uri:URI)=wrap(delegate.provider().getPath(uri))
    override fun newByteChannel(path:Path,options:MutableSet<out OpenOption>,vararg attrs:FileAttribute<*>):SeekableByteChannel {
        val channel=nio.newByteChannel(unwrap(path),options,*attrs)
        return object:SeekableByteChannel by channel {
            override fun read(dst:ByteBuffer):Int=channel.read(dst).also { if(it>0 && path.toString().endsWith(".flac"))audioBytesRead+=it }
        }
    }
    override fun newDirectoryStream(dir:Path,filter:DirectoryStream.Filter<in Path>):DirectoryStream<Path> {
        val stream=nio.newDirectoryStream(unwrap(dir)) { filter.accept(wrap(it)) }
        return object:DirectoryStream<Path> {
            override fun iterator()=stream.map(::wrap).toMutableList().iterator()
            override fun close()=stream.close()
        }
    }
    override fun createDirectory(dir:Path,vararg attrs:FileAttribute<*>)=nio.createDirectory(unwrap(dir),*attrs)
    override fun delete(path:Path)=nio.delete(unwrap(path))
    override fun copy(source:Path,target:Path,vararg options:CopyOption)=nio.copy(unwrap(source),unwrap(target),*options)
    override fun move(source:Path,target:Path,vararg options:CopyOption)=nio.move(unwrap(source),unwrap(target),*options)
    override fun isSameFile(path:Path,path2:Path)=nio.isSameFile(unwrap(path),unwrap(path2))
    override fun isHidden(path:Path)=nio.isHidden(unwrap(path))
    override fun getFileStore(path:Path)=nio.getFileStore(unwrap(path))
    override fun checkAccess(path:Path,vararg modes:AccessMode)=nio.checkAccess(unwrap(path),*modes)
    override fun <V:FileAttributeView> getFileAttributeView(path:Path,type:Class<V>,vararg options:LinkOption):V?=nio.getFileAttributeView(unwrap(path),type,*options)
    override fun <A:BasicFileAttributes> readAttributes(path:Path,type:Class<A>,vararg options:LinkOption):A=nio.readAttributes(unwrap(path),type,*options)
    override fun readAttributes(path:Path,attributes:String,vararg options:LinkOption):MutableMap<String,Any> {
        if(attributes.startsWith("unix:")) { ctimeRequests++;throw UnsupportedOperationException("unix view intentionally unavailable") }
        return nio.readAttributes(unwrap(path),attributes,*options)
    }
    override fun setAttribute(path:Path,attribute:String,value:Any,vararg options:LinkOption)=nio.setAttribute(unwrap(path),attribute,value,*options)
}
