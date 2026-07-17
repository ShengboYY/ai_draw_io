# syntax=docker/dockerfile:1.7
FROM alpine@sha256:fd791d74b68913cbb027c6546007b3f0d3bc45125f797758156952bc2d6daf40

RUN apk add --no-cache \
        mariadb-client=11.4.12-r0 \
        mariadb-connector-c=3.4.6-r0 \
    && addgroup --system migration \
    && adduser --system --ingroup migration --home /migration migration

WORKDIR /migration

# Package the reviewed release manifest and SQL so production does not download code at runtime.
ADD --chown=migration:migration --checksum=sha256:e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /migration/rds-global-bundle.pem
COPY --chown=migration:migration deploy/aws/database/run-migrations.sh /migration/run-migrations.sh
COPY --chown=migration:migration deploy/aws/database/release-20260717.manifest /migration/release-20260717.manifest
COPY --chown=migration:migration ai-agent-draw-io/docs/sql/migrations/ /migration/sql/

RUN chmod 0555 /migration/run-migrations.sh \
    && chmod 0444 /migration/rds-global-bundle.pem \
    && find /migration/sql -type f -name '*.sql' -exec chmod 0444 {} \;

USER migration
ENTRYPOINT ["/migration/run-migrations.sh"]
