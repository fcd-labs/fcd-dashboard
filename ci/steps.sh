# shellcheck shell=bash
# CI configuration of fcd-demo-dashboard (deelproduct Dashboard), read by `fcd ci` (fcd-demo-pipeline).

CI_COMPOSE_FILES="docker-compose.yml"
CI_SUBPRODUCT_CONTAINERS="dashboard-frontend dashboard-backend dashboard-store"
CI_OPENAPI="contracts/logline.openapi.yaml contracts/dashboard-api.openapi.yaml"
CI_CONTRACT_TARGETS="logline=contracts/logline.openapi.yaml@http://dashboard-backend:8080
dashboard-api=contracts/dashboard-api.openapi.yaml@http://dashboard-backend:8080"
# Same checks as sk-demo: the code does not break the contract (see fcd-demo issues #2 and #3).
CI_SCHEMATHESIS_ARGS="--checks not_a_server_error,status_code_conformance,content_type_conformance,response_schema_conformance,response_headers_conformance,negative_data_rejection"
CI_READY_URL="http://dashboard-frontend:8080/api/v1/overview"
CI_E2E_DIR="e2e"

# The database password: generated once per machine, never in git.
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

ci_freshness() { # versheid: how far libraries are behind. A library a major version behind closes the
  # security gate, with a reason per part (backend/maven, frontend/npm), so an explain covers only its part.
  # Returns 2 when it could not be determined.
  local p out lines major_be="" major_fe="" minor=0 failed=0
  for p in backend; do
    echo "== maven $p"
    out=$(docker run --rm -v "$R_REPO":/w -v fcd-m2:/root/.m2 -w "/w/$p" "$TOOL_MAVEN" mvn -B \
      org.codehaus.mojo:versions-maven-plugin:2.25.2:display-dependency-updates -DprocessDependencyManagement=false 2>&1) || { failed=1; continue; }
    # Lines like: [INFO]   group:artifact ......... 1.5.9 -> 3.0.9
    lines=$(printf '%s\n' "$out" | grep -- ' -> ' | sed 's/^\[INFO\] *//' || true)
    [ -n "$lines" ] && printf '%s\n' "$lines" || echo "up to date"
    major_be="$major_be$(printf '%s\n' "$lines" | awk 'NF { split($(NF-2), a, "."); split($NF, b, "."); if (b[1] + 0 > a[1] + 0) printf " %s %s->%s", $1, $(NF-2), $NF }')"
    minor=$((minor + $(printf '%s\n' "$lines" | grep -c . || true)))
  done
  echo "== npm frontend"
  docker build -q --target build -t fcd-tmp/dashboard-frontend-build "$R_REPO/frontend" >/dev/null || failed=1
  out=$(docker run --rm fcd-tmp/dashboard-frontend-build npm outdated --parseable 2>/dev/null || true)
  # --parseable: dir:name@wanted:name@current:name@latest:dependent (names can be scoped: @scope/name)
  local npm_awk='function ver(f) { return substr(f, match(f, /@[^@]*$/) + 1) } function name(f) { return substr(f, 1, match(f, /@[^@]*$/) - 1) }'
  printf '%s\n' "$out" | awk -F: "$npm_awk"' NF { print name($3) " " ver($3) " -> " ver($4) }'
  major_fe=$(printf '%s\n' "$out" | awk -F: "$npm_awk"' NF { split(ver($3), a, "."); split(ver($4), b, "."); if (b[1] + 0 > a[1] + 0) printf " %s", name($3) }')
  minor=$((minor + $(printf '%s\n' "$out" | grep -c . || true)))
  [ -n "$major_be" ] && s_fail "versheid backend (maven): een major-versie achter:$major_be"
  [ -n "$major_fe" ] && s_fail "versheid frontend (npm): een major-versie achter:$major_fe"
  [ "$failed" -eq 1 ] && return 2
  [ "$minor" -gt 0 ] && s_warn "versheid: $minor libraries hebben een nieuwere versie"
  return 0
}
