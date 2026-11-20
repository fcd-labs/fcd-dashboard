# Container dashboard-store: PostgreSQL (kader "Database: PostgreSQL"; PostgreSQL License, open source).
FROM postgres:18-alpine@sha256:ff64c9e3bdf8e2120107d3559de8db314e8305f7e38c5105903c6295876738ff
# Apply Alpine security fixes released after the base image was built.
# gosu only drops root privileges in the entrypoint; this container never runs as root, so it goes
# (it brought 1 critical and 25 high vulnerabilities in its Go standard library).
RUN apk upgrade --no-cache && rm -f /usr/local/bin/gosu
# The postgres user of the image.
USER 70:70
