FROM --platform=$BUILDPLATFORM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --prefer-offline --no-audit --no-fund
COPY web/ ./
RUN npm run build && \
    test -f /web/dist/index.html && \
    test -d /web/dist/web-assets

FROM --platform=$BUILDPLATFORM maven:3.9.10-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/ .
COPY --from=web-build /web/dist/index.html /src/planlock-app/src/main/resources/web/index.html
COPY --from=web-build /web/dist/web-assets/ /src/planlock-app/src/main/resources/static/web-assets/
ARG MAVEN_MIRROR_URL=
RUN set -eu; \
    set -- -B -ntp -DskipTests; \
    proxy_props=$(LC_ALL=C awk ' \
      function emit_proxy(prefix, raw,   rest, at, host, port, pnum) { \
        if (raw == "" || raw ~ /[\001-\040\042\047\134\177]/) return 0; \
        if (raw !~ /^[Hh][Tt][Tt][Pp]:\/\//) return 0; \
        rest = substr(raw, 8); \
        sub(/\/+$/, "", rest); \
        if (rest == "" || rest ~ /[\/?#]/) return 0; \
        while ((at = index(rest, "@")) > 0) rest = substr(rest, at + 1); \
        if (rest == "") return 0; \
        if (substr(rest, 1, 1) == "[") { \
          at = index(rest, "]:"); \
          if (at <= 2) return 0; \
          host = substr(rest, 2, at - 2); \
          port = substr(rest, at + 2); \
          if (host !~ /^[0-9A-Fa-f:.]+$/ || index(host, ":") == 0) return 0; \
        } else { \
          at = index(rest, ":"); \
          if (at <= 1) return 0; \
          host = substr(rest, 1, at - 1); \
          port = substr(rest, at + 1); \
          if (host !~ /^[A-Za-z0-9._-]+$/ || host ~ /^\.|\.$|\.\./) return 0; \
        } \
        if (port !~ /^[0-9]+$/) return 0; \
        pnum = port + 0; \
        if (pnum < 1 || pnum > 65535) return 0; \
        printf "-D%s.proxyHost=%s\n-D%s.proxyPort=%d\n", prefix, host, prefix, pnum; \
        return 1; \
      } \
      function emit_no_proxy(raw,   n, i, item, out, parts) { \
        if (raw == "" || raw ~ /[\001-\040\042\047\134\177]/) return 0; \
        n = split(raw, parts, ","); \
        out = ""; \
        for (i = 1; i <= n; i++) { \
          item = parts[i]; \
          if (item == "") continue; \
          if (substr(item, 1, 1) == "[" && substr(item, length(item), 1) == "]") item = substr(item, 2, length(item) - 2); \
          if (item == "" || item !~ /^[A-Za-z0-9.:*_\/-]+$/) return 0; \
          if (substr(item, 1, 1) == ".") item = "*" item; \
          out = (out == "" ? item : out "|" item); \
        } \
        if (out == "") return 0; \
        printf "-Dhttp.nonProxyHosts=%s\n", out; \
        return 1; \
      } \
      BEGIN { \
        has_proxy = 0; \
        if (emit_proxy("https", ENVIRON["HTTPS_PROXY"]) || emit_proxy("https", ENVIRON["https_proxy"])) has_proxy = 1; \
        if (emit_proxy("http", ENVIRON["HTTP_PROXY"]) || emit_proxy("http", ENVIRON["http_proxy"])) has_proxy = 1; \
        if (has_proxy) { \
          printf "-Daether.connector.http.useSystemProperties=true\n"; \
          if (!emit_no_proxy(ENVIRON["NO_PROXY"])) emit_no_proxy(ENVIRON["no_proxy"]); \
        } \
        exit 0; \
      } \
    '); \
    if [ -n "$proxy_props" ]; then \
      old_ifs=$IFS; \
      IFS=$(printf '\n.'); \
      IFS=${IFS%.}; \
      set -f; \
      for prop in $proxy_props; do \
        set -- "$@" "$prop"; \
      done; \
      set +f; \
      IFS=$old_ifs; \
    fi; \
    mirror_url=$(LC_ALL=C awk ' \
      BEGIN { \
        v = ENVIRON["MAVEN_MIRROR_URL"]; \
        if (v == "" || v ~ /[\001-\040\042\047\134\177]/ || v !~ /^https:\/\/[A-Za-z0-9._:\/-]+$/) exit 0; \
        rest = substr(v, 9); \
        slash = index(rest, "/"); \
        auth = (slash > 0 ? substr(rest, 1, slash - 1) : rest); \
        if (auth == "") exit 0; \
        colon = index(auth, ":"); \
        if (colon > 0) { \
          host = substr(auth, 1, colon - 1); \
          port = substr(auth, colon + 1); \
          if (port !~ /^[0-9]+$/) exit 0; \
          pnum = port + 0; \
          if (pnum < 1 || pnum > 65535) exit 0; \
        } else { \
          host = auth; \
        } \
        if (host == "" || host !~ /^[A-Za-z0-9._-]+$/ || host ~ /^\.|\.$|\.\./) exit 0; \
        printf "%s", v; \
        exit 0; \
      } \
    '); \
    if [ -n "$mirror_url" ]; then \
      trap 'rm -f /tmp/oriva-mvn-settings.xml' EXIT; \
      printf '%s\n' "<settings><mirrors><mirror><id>oriva-custom</id><mirrorOf>central</mirrorOf><url>${mirror_url}</url></mirror></mirrors></settings>" > /tmp/oriva-mvn-settings.xml; \
      set -- "$@" -s /tmp/oriva-mvn-settings.xml; \
    elif [ -n "${MAVEN_MIRROR_URL:-}" ]; then \
      printf '%s\n' 'Warning: ignoring invalid MAVEN_MIRROR_URL.' >&2; \
    fi; \
    set -- "$@" package; \
    mvn "$@" && \
    cp /src/planlock-app/target/planlock-app-*.jar /src/app.jar && \
    javac -d /health /src/docker/Healthcheck.java

FROM eclipse-temurin:21.0.8_9-jre-jammy
ARG VERSION=2.2.0
ARG REVISION=unknown
LABEL org.opencontainers.image.title="知序 ORIVA backend" \
      org.opencontainers.image.source="https://github.com/DzDenzel/zhixu-oriva" \
      org.opencontainers.image.version="$VERSION" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.licenses="MIT"
RUN groupadd --gid 10001 oriva && useradd --uid 10001 --gid oriva --no-create-home oriva
WORKDIR /opt/oriva
COPY --from=build --chown=10001:10001 /src/app.jar app.jar
COPY --from=build --chown=10001:10001 /health /opt/oriva/health
COPY --chown=10001:10001 backend/plugins /opt/oriva/plugins
ENV PLANLOCK_PLUGIN_DIRECTORY=/opt/oriva/plugins \
    PLANLOCK_VERSION=$VERSION
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD ["java", "-cp", "/opt/oriva/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/oriva/app.jar"]
