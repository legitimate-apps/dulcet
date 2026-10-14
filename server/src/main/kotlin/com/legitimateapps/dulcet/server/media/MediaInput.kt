package com.legitimateapps.dulcet.server.media

/** Only self-contained audio containers: playlist demuxers can open files outside the music root. */
val AUDIO_INPUT_ARGS = listOf(
    "-protocol_whitelist", "file",
    "-format_whitelist", "aac,aiff,ape,asf,asf_o,flac,mp3,ogg,mov,mp4,m4a,3gp,3g2,mj2,wav,wv,dsf,matroska,webm",
)
