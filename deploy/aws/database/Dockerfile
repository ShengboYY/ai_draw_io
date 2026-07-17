# syntax=docker/dockerfile:1.7
FROM mysql@sha256:d36d39a64cd12a5c1cc9e6aa2bfb5f8d4c81a2f6586e0a04a9ae13939db02209

WORKDIR /migration

# Package the reviewed release manifest and SQL so production does not download code at runtime.
COPY --chown=mysql:mysql deploy/aws/database/run-migrations.sh /migration/run-migrations.sh
COPY --chown=mysql:mysql deploy/aws/database/release-20260717.manifest /migration/release-20260717.manifest
COPY --chown=mysql:mysql ai-agent-draw-io/docs/sql/migrations/ /migration/sql/

RUN chmod 0555 /migration/run-migrations.sh \
    && find /migration/sql -type f -name '*.sql' -exec chmod 0444 {} \;

USER mysql
ENTRYPOINT ["/migration/run-migrations.sh"]
