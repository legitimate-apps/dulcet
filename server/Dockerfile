# Dulcet Server image: a jlink'd Java runtime plus ffmpeg on Alpine.
# Build the distribution first, then the image, from the repository root:
#   ./gradlew :server:installDist
#   docker build -f server/Dockerfile -t dulcet-server server/build/install/dulcet-server
# Run: docker run -p 4533:4533 -v /path/to/music:/music:ro -v dulcet-data:/data \
#        -e DULCET_ADMIN_USER=admin -e DULCET_ADMIN_PASSWORD=change-me dulcet-server
FROM eclipse-temurin:21-jdk-alpine AS jre
RUN jlink --add-modules java.base,java.sql,java.net.http,java.desktop,java.logging,java.naming,java.management,java.xml,jdk.unsupported,jdk.crypto.ec \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre

FROM alpine:3.20
RUN apk add --no-cache ffmpeg \
 && addgroup -S dulcet && adduser -S -G dulcet dulcet \
 && mkdir -p /music /data && chown dulcet:dulcet /data
COPY --from=jre /jre /opt/jre
COPY lib /opt/dulcet-server/lib
COPY bin /opt/dulcet-server/bin
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH \
    JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=50 -Xss512k -Djava.awt.headless=true" \
    DULCET_MUSIC=/music \
    DULCET_DATA=/data \
    DULCET_PORT=4533 \
    DULCET_RESCAN_INTERVAL=300
USER dulcet
EXPOSE 4533
VOLUME ["/data"]
ENTRYPOINT ["/opt/dulcet-server/bin/dulcet-server"]
CMD ["serve"]
