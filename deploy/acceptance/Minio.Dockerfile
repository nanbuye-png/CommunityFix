# Source fallback for the exact planned release, never masquerades as upstream image.
FROM golang:1.24.2-bookworm AS build
ARG MINIO_REVISION
RUN test -n "$MINIO_REVISION"
RUN git clone --filter=blob:none --no-checkout https://github.com/minio/minio.git /src
WORKDIR /src
RUN git checkout --detach "$MINIO_REVISION" && test "$(git rev-parse HEAD)" = "$MINIO_REVISION"
RUN CGO_ENABLED=0 go build -trimpath -o /minio .
FROM scratch
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /minio /minio
COPY --from=build /src/LICENSE /LICENSE
EXPOSE 9000
ENTRYPOINT ["/minio"]
