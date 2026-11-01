FROM golang:1.24.2-bookworm AS certificates
FROM scratch
LABEL org.opencontainers.image.source="https://github.com/minio/minio"
LABEL org.opencontainers.image.revision="0d7408fc9969caf07de6a8c3a84f9fbb10a6739e"
LABEL org.opencontainers.image.version="RELEASE.2025-04-22T22-12-26Z-source"
COPY --from=certificates /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY minio /minio
COPY LICENSE /LICENSE
EXPOSE 9000
ENTRYPOINT ["/minio"]
