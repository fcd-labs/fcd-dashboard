# shellcheck shell=bash
# How the Dashboard (deelproduct of FCD) is linted, tested and built, read by `fcd ci` (fcd-pipeline).

CI_COMPOSE_FILES="docker-compose.yml"
CI_SUBPRODUCT_CONTAINERS="dashboard-frontend dashboard-backend dashboard-store"
CI_OPENAPI="contracts/logline.openapi.yaml contracts/dashboard-api.openapi.yaml"
CI_CONTRACT_TARGETS="logline=contracts/logline.openapi.yaml@http://dashboard-backend:8080
dashboard-api=contracts/dashboard-api.openapi.yaml@http://dashboard-backend:8080"
# Which contract checks run is in the configuration of the showcase (Step integration-tests).
CI_READY_URL="http://dashboard-frontend:8080/api/v1/overview"
CI_E2E_DIR="e2e"

# The database password: generated once per showcase on this machine, never in git.
DASHBOARD_DB_PASSWORD=$(fcd_secret dashboard-db)
export DASHBOARD_DB_PASSWORD

ci_lint() { # ktlint (MIT) for Kotlin, Prettier (MIT) for the frontend
  local rc=0
  echo "== ktlint backend"
  docker run --rm -v "$R_REPO":/w -v fcd-m2:/root/.m2 -w /w/backend "$TOOL_MAVEN" \
    mvn -B -q com.github.gantsign.maven:ktlint-maven-plugin:3.7.1:check || rc=1
  echo "== prettier frontend"
  docker run --rm -e npm_config_cache=/tmp/npm -v "$R_REPO/frontend":/w:ro -w /w "$TOOL_NODE" \
    npx -y prettier@3.8.1 --check 'src/**/*.{ts,html,css}' || rc=1
  return $rc
}

ci_unit() {
  local v
  v=$(ver_get "$R_REPO" DASHBOARD_FRONTEND_VERSION); unit_from_dockerfile dashboard-frontend frontend "" --build-arg "APP_VERSION=$v"
  v=$(ver_get "$R_REPO" DASHBOARD_BACKEND_VERSION);  unit_from_dockerfile dashboard-backend . backend/Dockerfile --build-arg "APP_VERSION=$v"
}

ci_freshness() { # facts only: one line "freshness|part|name|current|latest" per library with a newer version.
  # The threshold is in the configuration of the showcase. Returns 2 when it could not be determined.
  local out failed=0
  echo "== maven backend"
  out=$(docker run --rm -v "$R_REPO":/w -v fcd-m2:/root/.m2 -w /w/backend "$TOOL_MAVEN" mvn -B \
    org.codehaus.mojo:versions-maven-plugin:2.25.2:display-dependency-updates -DprocessDependencyManagement=false 2>&1) || failed=1
  printf '%s\n' "$out" | grep -- ' -> ' | sed 's/^\[INFO\] *//' | awk 'NF { print "freshness|backend (maven)|" $1 "|" $(NF-2) "|" $NF }' | sort -u
  echo "== npm frontend"
  docker build -q --target build -t fcd-tmp/dashboard-frontend-build "$R_REPO/frontend" >/dev/null || failed=1
  out=$(docker run --rm fcd-tmp/dashboard-frontend-build npm outdated --parseable 2>/dev/null || true)
  # --parseable: dir:name@wanted:name@current:name@latest:dependent (names can be scoped: @scope/name)
  local npm_awk='function ver(f) { return substr(f, match(f, /@[^@]*$/) + 1) } function name(f) { return substr(f, 1, match(f, /@[^@]*$/) - 1) }'
  printf '%s\n' "$out" | awk -F: "$npm_awk"' NF { print "freshness|frontend (npm)|" name($3) "|" ver($3) "|" ver($4) }'
  [ "$failed" -eq 1 ] && return 2
  return 0
}
