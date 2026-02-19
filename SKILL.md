---
name: qa-jacoco-coverage
description: JaCoCo code coverage for functional API tests. Maven-first approach behind corporate firewalls. Auto-detects prerequisites, manages ports, handles sessions, generates and opens HTML reports with gap analysis.
---

# JaCoCo Coverage — Streamlined Maven Approach

## FLOW: Auto-Proceed with Maven (No Upfront Options)

When user selects JaCoCo from menu, DIRECTLY run prerequisites and proceed with Maven approach.
Do NOT show A/B/C options. Just do it. Only fallback if something fails.

```
User selects JaCoCo
  │
  ├─ Run prerequisites silently
  │   ├─ Dev project in workspace?      NO → "Add dev project folder. Cannot proceed."
  │   ├─ Dev project builds?            NO → "Build failed: [error]. Fix and retry."
  │   ├─ Test suite exists?             NO → "No tests found. Create tests first (option 1 or 7)."
  │   └─ All passed ↓
  │
  ├─ Show: "Using Maven plugin approach to download JaCoCo and track coverage."
  │        "This is the recommended approach. Press Ctrl+C or type 'stop' to cancel."
  │        "Alternatives: type 'ai-estimate' for AI analysis or 'manual' for manual setup."
  │
  └─ Proceed with Steps 1-10 automatically
```

If dev project cannot start (missing config, DB, etc.):
```
❌ Dev API failed to start. Common causes:
  - Missing application-dev.properties or env variables
  - Database not available locally
  - External service dependencies

To fix: Make the API runnable locally with any environment:
  java -jar target/app.jar --spring.profiles.active=local

Once it starts locally, retry JaCoCo (option 8 or type "jacoco").

Meanwhile, I can provide an AI-estimated coverage analysis.
Want AI-estimate? (type "yes" or "menu")
```

---

## STEP 1: Add Maven Plugins to Dev pom.xml

Show status:
```
━━━ STEP 1/10: CONFIGURING MAVEN JACOCO PLUGINS ━━━
```

Add to dev `<build><plugins>`:
```xml
<!-- JaCoCo Agent + CLI download via Maven (corporate proxy safe) -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>3.6.1</version>
    <executions>
        <execution>
            <id>download-jacoco-tools</id>
            <phase>package</phase>
            <goals><goal>copy</goal></goals>
            <configuration><artifactItems>
                <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.cli</artifactId>
                    <version>0.8.12</version><classifier>nodeps</classifier>
                    <outputDirectory>${project.build.directory}/jacoco-tools</outputDirectory>
                    <destFileName>jacoco-cli.jar</destFileName></artifactItem>
                <artifactItem><groupId>org.jacoco</groupId><artifactId>org.jacoco.agent</artifactId>
                    <version>0.8.12</version><classifier>runtime</classifier>
                    <outputDirectory>${project.build.directory}/jacoco-tools</outputDirectory>
                    <destFileName>jacoco-agent.jar</destFileName></artifactItem>
            </artifactItems></configuration>
        </execution>
    </executions>
</plugin>
```

Status: `✅ STEP 1 COMPLETE: Maven plugins added to pom.xml`

---

## STEP 2: ALWAYS Build Dev Project Fresh & Download JaCoCo

```
━━━ STEP 2/10: BUILDING DEV PROJECT & DOWNLOADING JACOCO ━━━
⚠️  ALWAYS build fresh — even mid-session. Stale builds cause 0% coverage.
```

```bash
cd {dev-project}/

# ALWAYS do a clean build — never skip this
# For multi-module: build from parent to compile all modules
mvn clean package -DskipTests 2>&1 | tail -10

BUILD_STATUS=$?
if [ $BUILD_STATUS -ne 0 ]; then
  echo "❌ STEP 2 FAILED: Build failed. Fix build errors before JaCoCo."
  exit 1
fi

# Verify JaCoCo JARs downloaded
if [ -f target/jacoco-tools/jacoco-agent.jar ] && [ -f target/jacoco-tools/jacoco-cli.jar ]; then
  echo "✅ JaCoCo JARs downloaded"
  ls -lh target/jacoco-tools/
else
  # Multi-module: check in bootable module's target
  BOOT_MODULE=$(find . -name "*.jar" -path "*/target/*" | grep -v original | grep -v jacoco | head -1)
  BOOT_DIR=$(dirname $(dirname $BOOT_MODULE))
  if [ -f "${BOOT_DIR}/target/jacoco-tools/jacoco-agent.jar" ]; then
    echo "✅ JaCoCo JARs found in ${BOOT_DIR}/target/jacoco-tools/"
  else
    echo "❌ STEP 2 FAILED: JaCoCo JARs not found."
  fi
fi

# For multi-module: identify all target/classes directories for report generation
echo "Class directories for coverage report:"
find . -type d -name "classes" -path "*/target/*" | grep -v test-classes
```

Status: `✅ STEP 2 COMPLETE: Fresh build + JaCoCo JARs ready`
```

---

## STEP 3: Free Up Ports (API Port + JaCoCo TCP Port)

```
━━━ STEP 3/10: CLEARING PORTS ━━━
```

```bash
# Detect API port from application.properties/yml
DEV_PORT=$(grep -r "server.port" {dev-project}/src/main/resources/ 2>/dev/null | grep -oP '\d+' | head -1)
DEV_PORT=${DEV_PORT:-8080}
echo "API port: ${DEV_PORT}"

JACOCO_PORT=6300
echo "JaCoCo TCP port: ${JACOCO_PORT}"

# Kill anything on API port
if lsof -ti:${DEV_PORT} > /dev/null 2>&1; then
  echo "Stopping process on port ${DEV_PORT}..."
  lsof -ti:${DEV_PORT} | xargs kill -9 2>/dev/null
  sleep 2
  echo "✅ Port ${DEV_PORT} cleared"
else
  echo "✅ Port ${DEV_PORT} already free"
fi

# Kill anything on JaCoCo TCP port
if lsof -ti:${JACOCO_PORT} > /dev/null 2>&1; then
  echo "Stopping process on port ${JACOCO_PORT}..."
  lsof -ti:${JACOCO_PORT} | xargs kill -9 2>/dev/null
  sleep 2
  echo "✅ Port ${JACOCO_PORT} cleared"
else
  echo "✅ Port ${JACOCO_PORT} already free"
fi

echo "✅ STEP 3 COMPLETE: Ports ready"
```

---

## STEP 4: Session Management (New or Existing)

```
━━━ STEP 4/10: COVERAGE SESSION ━━━
```

Ask user:
```
Do you want to:
  A. Start a NEW coverage session (fresh report)
  B. ADD to an existing coverage session (accumulate coverage)

Default: A (new session). Type A or B.
```

If B (existing session) and `target/coverage-functional.exec` exists:
```bash
# Backup existing exec file — will merge later
cp target/coverage-functional.exec target/coverage-functional-prev.exec
echo "✅ Previous session backed up. Will merge after this run."
```

---

## STEP 5: Start Dev API with JaCoCo Agent

```
━━━ STEP 5/10: STARTING API WITH JACOCO AGENT ━━━
```

**CRITICAL: Always use `java -jar` with explicit `-javaagent`. NEVER use `mvn spring-boot:run`.**
`spring-boot:run` spawns a child JVM — the JaCoCo agent may not attach properly.

```bash
cd {dev-project}/

JACOCO_AGENT="target/jacoco-tools/jacoco-agent.jar"
APP_JAR=$(ls target/*.jar | grep -v original | grep -v jacoco | grep -v sources | head -1)
PACKAGE="com.fmr.fi.analytics"  # Detected from source code

echo "Starting: java -javaagent:${JACOCO_AGENT} -jar ${APP_JAR}"
echo "Package filter: ${PACKAGE}.*"

java \
  -javaagent:${JACOCO_AGENT}=output=tcpserver,address=*,port=${JACOCO_PORT},includes=${PACKAGE}.* \
  -jar ${APP_JAR} \
  > /tmp/jacoco-app.log 2>&1 &

DEV_PID=$!
echo "PID: ${DEV_PID}"

# Wait with progress
echo -n "Waiting for API"
STARTED=false
for i in $(seq 1 90); do
  if curl -sf http://localhost:${DEV_PORT}/actuator/health > /dev/null 2>&1; then
    STARTED=true
    break
  fi
  sleep 1; printf "."
done
echo ""

if [ "$STARTED" = true ]; then
  echo "✅ STEP 5 COMPLETE: API RUNNING"
  echo "   PID: ${DEV_PID}"
  echo "   API: http://localhost:${DEV_PORT}"
  echo "   JaCoCo: listening on port ${JACOCO_PORT}"
else
  echo "❌ STEP 5 FAILED: API did not start within 90 seconds"
  echo "   Check logs: tail -50 /tmp/jacoco-app.log"
  echo ""
  echo "   Common fixes:"
  echo "   - Add --spring.profiles.active=local to the java command"
  echo "   - Set required env variables (DB_URL, etc.)"
  echo "   - Check if dependencies (DB, Redis, etc.) are running"
  echo ""
  echo "   Fix the startup issue and type 'jacoco' to retry."
  echo "   Or type 'ai-estimate' for AI-based coverage analysis."
  kill $DEV_PID 2>/dev/null
fi
```

---

## STEP 6: Ask Test Execution Preferences

```
━━━ STEP 6/10: TEST EXECUTION CONFIG ━━━

How would you like to run the tests?
  A. Run ENTIRE test suite (all tags, default env) ← Default
  B. Run specific TAGS (e.g., @smoke, @customer-management)
  C. Run specific ENVIRONMENT (e.g., local, qa)
  D. Run specific tags AND environment

Default: A (full suite against local API). Type A/B/C/D or just press Enter.
```

If B: Ask for tag expression.
If C: Ask for env name.
If D: Ask for both.
Default: full suite with `local` env pointing to `http://localhost:{DEV_PORT}`.

---

## STEP 7: Run Functional Tests

```
━━━ STEP 7/10: EXECUTING TESTS ━━━
```

```bash
cd {test-project}/

# Karate example (adapt for Cucumber):
mvn clean test -Dkarate.env=local 2>&1 | tee /tmp/jacoco-test-run.log

# Extract results
TOTAL=$(grep -oP 'Tests run: \K\d+' /tmp/jacoco-test-run.log | tail -1)
PASS=$(grep -oP 'Tests run: \d+.*Failures: \K\d+' /tmp/jacoco-test-run.log | tail -1)
FAIL=$(grep -oP 'Failures: \K\d+' /tmp/jacoco-test-run.log | tail -1)
SKIP=$(grep -oP 'Skipped: \K\d+' /tmp/jacoco-test-run.log | tail -1)

echo ""
echo "✅ STEP 7 COMPLETE: TEST EXECUTION"
echo "   Total: ${TOTAL:-?} | Passed: $((TOTAL-FAIL-SKIP)) | Failed: ${FAIL:-0} | Skipped: ${SKIP:-0}"
```

---

## STEP 8: Dump Coverage Data

```
━━━ STEP 8/10: DUMPING COVERAGE DATA ━━━
```

```bash
cd {dev-project}/
java -jar target/jacoco-tools/jacoco-cli.jar dump \
  --address localhost --port ${JACOCO_PORT} \
  --destfile target/coverage-functional.exec

EXEC_SIZE=$(wc -c < target/coverage-functional.exec 2>/dev/null || echo 0)
echo "✅ STEP 8 COMPLETE: Coverage dumped (${EXEC_SIZE} bytes)"

# Merge with previous session if exists
if [ -f target/coverage-functional-prev.exec ]; then
  java -jar target/jacoco-tools/jacoco-cli.jar merge \
    target/coverage-functional-prev.exec target/coverage-functional.exec \
    --destfile target/coverage-functional-merged.exec
  mv target/coverage-functional-merged.exec target/coverage-functional.exec
  rm target/coverage-functional-prev.exec
  echo "   Merged with previous session"
fi
```

---

## STEP 9: Generate HTML Report & Open

```
━━━ STEP 9/10: GENERATING COVERAGE REPORT ━━━
```

```bash
cd {dev-project}/

# For single module:
# java -jar target/jacoco-tools/jacoco-cli.jar report target/coverage-functional.exec \
#   --classfiles target/classes --sourcefiles src/main/java ...

# For multi-module: include ALL module class dirs and source dirs
CLASS_DIRS=$(find . -type d -name "classes" -path "*/target/*" ! -path "*/test-classes/*" | sed 's/^/--classfiles /' | tr '\n' ' ')
SRC_DIRS=$(find . -type d -name "java" -path "*/src/main/*" | sed 's/^/--sourcefiles /' | tr '\n' ' ')

JACOCO_CLI="target/jacoco-tools/jacoco-cli.jar"
# Check in boot module if not at root
[ ! -f "$JACOCO_CLI" ] && JACOCO_CLI=$(find . -name "jacoco-cli.jar" -path "*/jacoco-tools/*" | head -1)

java -jar ${JACOCO_CLI} report target/coverage-functional.exec \
  ${CLASS_DIRS} \
  ${SRC_DIRS} \
  --html target/coverage-report \
  --csv target/coverage-report/coverage.csv \
  --xml target/coverage-report/coverage.xml \
  --name "Functional API Test Coverage"

echo "✅ STEP 9 COMPLETE: Report generated"
echo "   📁 target/coverage-report/index.html"

# Auto-open report in browser
if command -v open &> /dev/null; then
  open target/coverage-report/index.html
elif command -v xdg-open &> /dev/null; then
  xdg-open target/coverage-report/index.html
elif command -v start &> /dev/null; then
  start target/coverage-report/index.html
else
  echo "   Open manually: target/coverage-report/index.html"
fi
```

---

## STEP 10: Stop API + Summary + Recommendations

```
━━━ STEP 10/10: CLEANUP & ANALYSIS ━━━
```

```bash
# Stop the API
kill $DEV_PID 2>/dev/null
echo "✅ Dev API stopped (PID: ${DEV_PID})"
```

### Display Full Summary
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 JACOCO COVERAGE — FINAL SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 EXECUTION STATUS:
  ✅ Maven build & JaCoCo download    — SUCCESS
  ✅ Port cleanup (8080, 6300)         — SUCCESS
  ✅ API started with JaCoCo agent     — SUCCESS (PID: XXXX)
  ✅ Test execution                    — X passed, Y failed, Z skipped
  ✅ Coverage dump                     — SUCCESS (XXXXX bytes)
  ✅ Report generation                 — SUCCESS
  ✅ API stopped                       — SUCCESS

📈 OVERALL COVERAGE:
  Instructions: XX%
  Lines:        XX%
  Branches:     XX%
  Methods:      XX%

📁 COVERAGE BY FUNCTIONALITY:
  ┌──────────────────────────────┬───────┬──────────┐
  │ Class/Controller             │ Lines │ Branches │
  ├──────────────────────────────┼───────┼──────────┤
  │ CustomerController           │  75%  │   60%    │
  │ CustomerService              │  65%  │   45%    │
  │ OrderController              │  40%  │   30%    │
  │ AuthService                  │  90%  │   85%    │
  │ PaymentService               │  20%  │   10%    │
  └──────────────────────────────┴───────┴──────────┘

🔍 GAP ANALYSIS:
  ❌ LOW COVERAGE (<50%):
     • OrderController (40%) — Missing: PUT, DELETE, error handling
     • PaymentService (20%) — Missing: refund flow, validation, timeout handling

  ⚠️ MEDIUM COVERAGE (50-75%):
     • CustomerService (65%) — Missing: edge cases in search, pagination

  ✅ GOOD COVERAGE (>75%):
     • CustomerController (75%)
     • AuthService (90%)

💡 RECOMMENDATIONS:
  1. Add 4 test scenarios for OrderController PUT/DELETE endpoints → est. +15% coverage
  2. Add 3 test scenarios for PaymentService refund flow → est. +12% coverage
  3. Add 2 edge case scenarios for CustomerService search → est. +5% coverage
  4. Total potential with suggested tests: ~XX% → ~YY%

🔄 NEXT STEPS:
  → Type "menu" to go back and create additional test cases
  → Type "jacoco" to re-run coverage after adding tests (choose "add to session")
  → Report saved at: target/coverage-report/index.html

📌 TO REVERT DEV POM CHANGES:
  git checkout {dev-project}/pom.xml
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

Parse the CSV to generate actual numbers:
```bash
tail -n +2 target/coverage-report/coverage.csv | while IFS=',' read -r g p c im ic bm bc lm lc cm cc mm mc; do
  ti=$((im+ic)); tl=$((lm+lc)); tb=$((bm+bc))
  [ "$ti" -gt 0 ] && pi=$((ic*100/ti)) || pi=0
  [ "$tl" -gt 0 ] && pl=$((lc*100/tl)) || pl=0
  [ "$tb" -gt 0 ] && pb=$((bc*100/tb)) || pb=0
  [ "$ti" -gt 0 ] && printf "  %-40s Lines:%3d%% | Branch:%3d%%\n" "$c" "$pl" "$pb"
done

echo ""
echo "━━━━ OVERALL ━━━━"
tail -n +2 target/coverage-report/coverage.csv | awk -F',' '
  {tim+=$4;tic+=$5;tbm+=$6;tbc+=$7;tlm+=$8;tlc+=$9;tmm+=$12;tmc+=$13}
  END{
    if(tim+tic>0) printf "  Instructions: %d%% (%d/%d)\n",tic*100/(tim+tic),tic,tim+tic
    if(tlm+tlc>0) printf "  Lines:        %d%% (%d/%d)\n",tlc*100/(tlm+tlc),tlc,tlm+tlc
    if(tbm+tbc>0) printf "  Branches:     %d%% (%d/%d)\n",tbc*100/(tbm+tbc),tbc,tbm+tbc
    if(tmm+tmc>0) printf "  Methods:      %d%% (%d/%d)\n",tmc*100/(tmm+tmc),tmc,tmm+tmc
  }'
```

---

## FALLBACK: AI-ESTIMATED COVERAGE

Only used when JaCoCo cannot run (no dev project, build fails, API won't start).

```
⚠️ AI-ESTIMATED COVERAGE (Not Instrumented)
   This is approximate analysis based on mapping tests to code.
   Accuracy may vary. Use for directional guidance only.

   For accurate coverage: fix dev project startup and run JaCoCo (option 8).
```
